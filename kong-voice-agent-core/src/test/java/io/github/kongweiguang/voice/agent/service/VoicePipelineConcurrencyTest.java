package io.github.kongweiguang.voice.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.support.NoopStreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.eou.NoopEouDetector;
import io.github.kongweiguang.voice.agent.llm.LlmChunk;
import io.github.kongweiguang.voice.agent.llm.LlmRequest;
import io.github.kongweiguang.voice.agent.playback.InterruptionManager;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.playback.SessionAudioPlaybackPolicy;
import io.github.kongweiguang.voice.agent.session.SessionManager;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.support.TestSessionStates;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;
import io.github.kongweiguang.v1.json.Json;
import io.github.kongweiguang.voice.agent.vad.VadDecision;
import io.github.kongweiguang.voice.agent.vad.VadEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证语音流水线在并发场景下的顺序性和 turnId 隔离行为。
 *
 * @author kongweiguang
 */
@Tag("pipeline")
@DisplayName("流水线并发场景")
class VoicePipelineConcurrencyTest {
    /**
     * 同一 session 的音频帧即使被多个虚拟线程同时提交，也必须串行进入 VAD。
     */
    @Test
    @DisplayName("同一 session 的音频处理会串行进入 VAD")
    void serializesAudioProcessingPerSession() throws Exception {
        BlockingVadEngine vadEngine = new BlockingVadEngine();
        PlaybackDispatcher dispatcher = new PlaybackDispatcher();
        try (ExecutorService audioExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            VoicePipelineService service = new VoicePipelineService(
                    vadEngine,
                    new NoopEouDetector(),
                    eouConfig(),
                    this::replyOnce,
                    this::ttsEcho,
                    dispatcher,
                    new InterruptionManager(dispatcher),
                    new SessionAudioPlaybackPolicy(),
                    sessionManager(),
                    audioExecutor,
                    directExecutor(),
                    List.of()
            );
            SessionState session = TestSessionStates.create("s1");
            CapturingWebSocketSession ws = new CapturingWebSocketSession();

            service.acceptAudio(session, ws, new byte[]{1, 2});
            assertThat(vadEngine.firstCallEntered.await(2, TimeUnit.SECONDS)).isTrue();

            service.acceptAudio(session, ws, new byte[]{3, 4});
            Thread.sleep(150L);

            assertThat(vadEngine.startedCalls.get()).isEqualTo(1);
            assertThat(vadEngine.maxConcurrentCalls.get()).isEqualTo(1);

            vadEngine.allowFirstCallToFinish.countDown();

            assertThat(vadEngine.allCallsFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(vadEngine.startedCalls.get()).isEqualTo(2);
            assertThat(vadEngine.maxConcurrentCalls.get()).isEqualTo(1);
        }
    }

    /**
     * 旧 turn 的异步 LLM/TTS 回调在并发切换到新 turn 后必须被丢弃，避免污染当前会话。
     */
    @Test
    @DisplayName("并发文本 turn 切换后旧 turn 的异步结果会被丢弃")
    void dropsLateChunksFromInterruptedTurn() throws Exception {
        PlaybackDispatcher dispatcher = new PlaybackDispatcher();
        CountDownLatch firstChunkSent = new CountDownLatch(1);
        CountDownLatch allowLateOldChunk = new CountDownLatch(1);
        AtomicReference<String> firstTurnId = new AtomicReference<>();
        AtomicReference<String> secondTurnId = new AtomicReference<>();

        try (ExecutorService agentExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            VoicePipelineService service = new VoicePipelineService(
                    new SilentVadEngine(),
                    new NoopEouDetector(),
                    eouConfig(),
                    (request, consumer) -> streamConcurrentReplies(
                            request,
                            consumer,
                            firstTurnId,
                            secondTurnId,
                            firstChunkSent,
                            allowLateOldChunk
                    ),
                    this::ttsEcho,
                    dispatcher,
                    new InterruptionManager(dispatcher),
                    new SessionAudioPlaybackPolicy(),
                    sessionManager(),
                    directExecutor(),
                    agentExecutor,
                    List.of()
            );
            SessionState session = TestSessionStates.create("s1");
            CapturingWebSocketSession ws = new CapturingWebSocketSession();

            service.acceptText(session, ws, "first");
            assertThat(firstChunkSent.await(2, TimeUnit.SECONDS)).isTrue();
            waitUntil(session::agentSpeaking, 2_000L);

            service.acceptText(session, ws, "second");
            allowLateOldChunk.countDown();

            waitUntil(() -> ws.countEvents("tts_audio_chunk") >= 2, 2_000L);

            List<JsonNode> events = ws.sentEvents();
            List<JsonNode> textChunks = filterEvents(events, "agent_text_chunk");
            List<JsonNode> ttsChunks = filterEvents(events, "tts_audio_chunk");

            assertThat(filterEvents(events, "playback_stop"))
                    .anyMatch(node -> firstTurnId.get().equals(node.get("turnId").asText()));
            assertThat(filterEvents(events, "turn_interrupted"))
                    .anyMatch(node -> firstTurnId.get().equals(node.get("turnId").asText()));

            assertThat(textChunks)
                    .extracting(node -> node.at("/payload/text").asText())
                    .contains("old-1", "new")
                    .doesNotContain("old-2");
            assertThat(textChunks)
                    .filteredOn(node -> "new".equals(node.at("/payload/text").asText()))
                    .allMatch(node -> secondTurnId.get().equals(node.get("turnId").asText()));
            assertThat(ttsChunks)
                    .extracting(node -> node.at("/payload/text").asText())
                    .contains("old-1", "new")
                    .doesNotContain("old-2");
        }
    }

    /**
     * 断连后即使后台 LLM 还在回调，也不能再向已关闭连接发送旧 turn 结果。
     */
    @Test
    @DisplayName("断连后挂起的异步旧 turn 结果不会再发送")
    void dropsAsyncChunksAfterDisconnect() throws Exception {
        PlaybackDispatcher dispatcher = new PlaybackDispatcher();
        CountDownLatch firstChunkSent = new CountDownLatch(1);
        CountDownLatch allowLateChunk = new CountDownLatch(1);
        AtomicReference<String> turnId = new AtomicReference<>();

        try (ExecutorService agentExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            VoicePipelineService service = new VoicePipelineService(
                    new SilentVadEngine(),
                    new NoopEouDetector(),
                    eouConfig(),
                    (request, consumer) -> {
                        turnId.set(request.turnId());
                        consumer.accept(new LlmChunk(request.turnId(), 0, "before-close", false));
                        firstChunkSent.countDown();
                        awaitLatch(allowLateChunk);
                        consumer.accept(new LlmChunk(request.turnId(), 1, "after-close", true));
                    },
                    this::ttsEcho,
                    dispatcher,
                    new InterruptionManager(dispatcher),
                    new SessionAudioPlaybackPolicy(),
                    sessionManager(),
                    directExecutor(),
                    agentExecutor,
                    List.of()
            );
            SessionState session = TestSessionStates.create("s1");
            ClosableCapturingWebSocketSession ws = new ClosableCapturingWebSocketSession();

            service.acceptText(session, ws, "disconnect");
            assertThat(firstChunkSent.await(2, TimeUnit.SECONDS)).isTrue();
            waitUntil(() -> ws.countEvents("tts_audio_chunk") >= 1, 2_000L);

            int sentBeforeClose = ws.sentCount();
            ws.open(false);
            session.clear();
            dispatcher.release(ws);
            allowLateChunk.countDown();
            Thread.sleep(150L);

            List<JsonNode> events = ws.sentEvents();
            assertThat(ws.sentCount()).isEqualTo(sentBeforeClose);
            assertThat(session.isCurrentTurn(turnId.get())).isFalse();
            assertThat(filterEvents(events, "agent_text_chunk"))
                    .extracting(node -> node.at("/payload/text").asText())
                    .contains("before-close")
                    .doesNotContain("after-close");
            assertThat(filterEvents(events, "tts_audio_chunk"))
                    .extracting(node -> node.at("/payload/text").asText())
                    .contains("before-close")
                    .doesNotContain("after-close");
        }
    }

    /**
     * 为并发 turn 切换场景构造可控的 LLM 流。
     */
    private void streamConcurrentReplies(LlmRequest request,
                                         Consumer<LlmChunk> consumer,
                                         AtomicReference<String> firstTurnId,
                                         AtomicReference<String> secondTurnId,
                                         CountDownLatch firstChunkSent,
                                         CountDownLatch allowLateOldChunk) {
        if ("first".equals(request.finalTranscript())) {
            firstTurnId.set(request.turnId());
            consumer.accept(new LlmChunk(request.turnId(), 0, "old-1", false));
            firstChunkSent.countDown();
            awaitLatch(allowLateOldChunk);
            consumer.accept(new LlmChunk(request.turnId(), 1, "old-2", true));
            return;
        }
        secondTurnId.set(request.turnId());
        consumer.accept(new LlmChunk(request.turnId(), 0, "new", true));
    }

    /**
     * 并发测试只需最简单的单段回复实现。
     */
    private void replyOnce(LlmRequest request, Consumer<LlmChunk> consumer) {
        consumer.accept(new LlmChunk(request.turnId(), 0, "ok", true));
    }

    /**
     * 用文本字节回显构造稳定的 TTS 结果，便于断言事件内容。
     */
    private List<TtsChunk> ttsEcho(String turnId, int startSeq, String text, boolean lastTextChunk) {
        return List.of(new TtsChunk(turnId, startSeq, lastTextChunk, text.getBytes(StandardCharsets.UTF_8), text));
    }

    /**
     * 文本并发测试不需要真正异步音频执行，直接在当前线程推进即可。
     */
    private Executor directExecutor() {
        return Runnable::run;
    }

    /**
     * 提供最小可用的会话注册表，满足流水线通过 sessionId 回查控制连接的依赖。
     */
    private SessionManager sessionManager() {
        return new SessionManager((sessionId, format) -> new NoopStreamingAsrAdapter(),
                AudioFormatSpec.DEFAULT,
                eouConfig());
    }

    /**
     * 使用默认 EOU 配置构造测试服务，避免测试因为端点策略缺失而偏离真实装配。
     */
    private EouConfig eouConfig() {
        return new EouConfig(true, null, null, null, true, 0.5, 500, 1600, 300, "zh");
    }

    /**
     * 过滤某一类协议事件，便于并发断言只关注关键消息。
     */
    private List<JsonNode> filterEvents(List<JsonNode> events, String type) {
        return events.stream()
                .filter(node -> type.equals(node.get("type").asText()))
                .toList();
    }

    /**
     * 以小步轮询等待异步条件成立，避免测试直接依赖不稳定的睡眠时长。
     */
    private void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20L);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    /**
     * 将受检异常统一转为断言失败，简化并发辅助逻辑。
     */
    private void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待并发测试信号时被中断", ex);
        }
    }

    /**
     * 第一帧 VAD 故意阻塞，用于验证第二个音频任务不会绕过会话锁并发进入。
     */
    private static final class BlockingVadEngine implements VadEngine {
        /**
         * 第一帧已经进入 VAD 的信号。
         */
        private final CountDownLatch firstCallEntered = new CountDownLatch(1);
        /**
         * 允许第一帧继续执行的信号。
         */
        private final CountDownLatch allowFirstCallToFinish = new CountDownLatch(1);
        /**
         * 两次 VAD 调用都完成后的信号。
         */
        private final CountDownLatch allCallsFinished = new CountDownLatch(2);
        /**
         * 已开始的 VAD 调用数。
         */
        private final AtomicInteger startedCalls = new AtomicInteger();
        /**
         * 当前正在执行的 VAD 调用数。
         */
        private final AtomicInteger concurrentCalls = new AtomicInteger();
        /**
         * 观测到的最大并发调用数。
         */
        private final AtomicInteger maxConcurrentCalls = new AtomicInteger();

        @Override
        public VadDecision detect(String turnId, byte[] pcm) {
            int started = startedCalls.incrementAndGet();
            int concurrent = concurrentCalls.incrementAndGet();
            maxConcurrentCalls.accumulateAndGet(concurrent, Math::max);
            try {
                if (started == 1) {
                    firstCallEntered.countDown();
                    awaitLatch(allowFirstCallToFinish);
                }
                return new VadDecision(turnId, 0D, false, Instant.now());
            } finally {
                concurrentCalls.decrementAndGet();
                allCallsFinished.countDown();
            }
        }

        @Override
        public boolean modelBacked() {
            return false;
        }

        @Override
        public void close() {
        }

        /**
         * 在静态内部类中复用外层等待逻辑。
         */
        private void awaitLatch(CountDownLatch latch) {
            try {
                assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待 VAD 并发测试信号时被中断", ex);
            }
        }
    }

    /**
     * 文本并发测试不会真正走到音频 VAD，这里只满足依赖注入。
     */
    private static final class SilentVadEngine implements VadEngine {
        @Override
        public VadDecision detect(String turnId, byte[] pcm) {
            return new VadDecision(turnId, 0D, false, Instant.now());
        }

        @Override
        public boolean modelBacked() {
            return false;
        }

        @Override
        public void close() {
        }
    }

    /**
     * 线程安全地收集异步 WebSocket 下行消息，便于并发断言。
     */
    private static class CapturingWebSocketSession implements WebSocketSession {
        /**
         * 当前测试连接 id。
         */
        private final String id = UUID.randomUUID().toString();
        /**
         * 记录所有已发送的文本帧。
         */
        protected final List<String> sent = new CopyOnWriteArrayList<>();

        /**
         * 将 JSON 文本帧解析为事件对象列表。
         */
        List<JsonNode> sentEvents() throws IOException {
            List<JsonNode> events = new ArrayList<>();
            for (String message : sent) {
                events.add(Json.node(message));
            }
            return events;
        }

        /**
         * 统计某一类事件出现次数。
         */
        int countEvents(String type) {
            return (int) sent.stream()
                    .map(Json::node)
                    .filter(node -> type.equals(node.get("type").asText()))
                    .count();
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/ws/agent");
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return new HttpHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return Map.of();
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 0;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 0;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            sent.add(String.valueOf(message.getPayload()));
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }

        @Override
        public void close(CloseStatus status) {
        }
    }

    /**
     * 允许测试主动切换连接开关，用于验证断连后的异步丢弃行为。
     */
    private static final class ClosableCapturingWebSocketSession extends CapturingWebSocketSession {
        /**
         * 当前连接是否仍处于打开状态。
         */
        private volatile boolean open = true;

        /**
         * 修改连接状态，模拟真实 WebSocket 断开。
         */
        void open(boolean open) {
            this.open = open;
        }

        /**
         * 返回当前已发送事件条数。
         */
        int sentCount() {
            return sent.size();
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }
}
