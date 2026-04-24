package io.github.kongweiguang.voice.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.eou.NoopEouDetector;
import io.github.kongweiguang.voice.agent.llm.LlmChunk;
import io.github.kongweiguang.voice.agent.llm.LlmRequest;
import io.github.kongweiguang.voice.agent.playback.InterruptionManager;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;
import io.github.kongweiguang.voice.agent.tts.TtsOrchestrator;
import io.github.kongweiguang.voice.agent.turn.TurnCancellationCoordinator;
import io.github.kongweiguang.voice.agent.util.JsonUtils;
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
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证客户端 audio_end 显式提交边界的异步执行、错误收口和事件顺序。
 *
 * @author kongweiguang
 */
@Tag("pipeline")
@DisplayName("音频结束提交流水线")
class VoicePipelineAudioEndTest {
    @Test
    @DisplayName("audio_end 不阻塞调用线程，异步提交 ASR 后进入 LLM/TTS")
    void commitsAudioEndAsynchronously() throws Exception {
        PlaybackDispatcher dispatcher = new PlaybackDispatcher();
        QueuedExecutor audioExecutor = new QueuedExecutor();
        RecordingAsrAdapter asrAdapter = new RecordingAsrAdapter("你好");
        VoicePipelineService service = service(dispatcher, audioExecutor, asrAdapter);
        SessionState session = session(asrAdapter);
        String turnId = session.nextTurnId();
        CapturingWebSocketSession ws = new CapturingWebSocketSession();

        service.commitAudioEnd(session, ws);

        assertThat(asrAdapter.commitCalls).hasValue(0);
        assertThat(ws.sent).isEmpty();

        audioExecutor.runNext();

        assertThat(asrAdapter.commitCalls).hasValue(1);
        List<JsonNode> events = ws.sentEvents();
        assertThat(events).extracting(node -> node.get("type").asText())
                .containsSubsequence("state_changed", "asr_final", "agent_thinking", "agent_text_chunk", "tts_audio_chunk");
        assertThat(events.getFirst().at("/payload/state").asText()).isEqualTo("USER_TURN_COMMITTED");
        assertThat(events.getFirst().get("turnId").asText()).isEqualTo(turnId);
    }

    @Test
    @DisplayName("audio_end 的 ASR 异常会转成 asr_failed")
    void convertsAsrFailureToErrorEvent() throws Exception {
        PlaybackDispatcher dispatcher = new PlaybackDispatcher();
        QueuedExecutor audioExecutor = new QueuedExecutor();
        RecordingAsrAdapter asrAdapter = new RecordingAsrAdapter("ignored");
        asrAdapter.failOnCommit = true;
        VoicePipelineService service = service(dispatcher, audioExecutor, asrAdapter);
        SessionState session = session(asrAdapter);
        session.nextTurnId();
        CapturingWebSocketSession ws = new CapturingWebSocketSession();

        service.commitAudioEnd(session, ws);
        audioExecutor.runNext();

        JsonNode error = ws.sentEvents().stream()
                .filter(node -> "error".equals(node.get("type").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(error.at("/payload/code").asText()).isEqualTo("asr_failed");
        assertThat(error.at("/payload/message").asText()).contains("ASR boom");
    }

    private VoicePipelineService service(PlaybackDispatcher dispatcher, Executor audioExecutor, StreamingAsrAdapter asrAdapter) {
        return new VoicePipelineService(
                new NoopVadEngine(),
                new NoopEouDetector(),
                eouConfig(),
                this::replyOnce,
                (turnId, startSeq, text, lastTextChunk) -> List.of(new TtsChunk(turnId, startSeq, lastTextChunk, text.getBytes(StandardCharsets.UTF_8), text)),
                dispatcher,
                new InterruptionManager(dispatcher, new TurnCancellationCoordinator(noopTtsOrchestrator())),
                audioExecutor,
                Runnable::run,
                List.of()
        );
    }

    private SessionState session(StreamingAsrAdapter asrAdapter) {
        return new SessionState("s1", io.github.kongweiguang.voice.agent.audio.AudioFormatSpec.DEFAULT, (sessionId, format) -> asrAdapter, eouConfig());
    }

    private void replyOnce(LlmRequest request, Consumer<LlmChunk> consumer) {
        consumer.accept(new LlmChunk(request.turnId(), 0, "收到：" + request.finalTranscript(), true));
    }

    private EouConfig eouConfig() {
        return new EouConfig(true, null, null, null, true, 0.5, 500, 1600, 300, "zh");
    }

    private TtsOrchestrator noopTtsOrchestrator() {
        return (turnId, startSeq, text, lastTextChunk) -> List.of();
    }

    /**
     * 测试专用执行器，先记录任务，便于证明 audio_end 没有同步提交 ASR。
     */
    private static final class QueuedExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            tasks.removeFirst().run();
        }
    }

    /**
     * 记录 commit 调用次数并按需抛出异常的 ASR stub。
     */
    private static final class RecordingAsrAdapter implements StreamingAsrAdapter {
        private final AtomicInteger commitCalls = new AtomicInteger();
        private final String transcript;
        private boolean failOnCommit;

        private RecordingAsrAdapter(String transcript) {
            this.transcript = transcript;
        }

        @Override
        public java.util.Optional<AsrUpdate> acceptAudio(String turnId, byte[] pcm) {
            return java.util.Optional.empty();
        }

        @Override
        public AsrUpdate commitTurn(String turnId) {
            commitCalls.incrementAndGet();
            if (failOnCommit) {
                throw new IllegalStateException("ASR boom");
            }
            return AsrUpdate.finalUpdate(turnId, transcript);
        }

        @Override
        public void close() {
        }
    }

    /**
     * 文本结束测试不会触发 VAD，该实现只满足构造依赖。
     */
    private static final class NoopVadEngine implements VadEngine {
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
     * 收集服务端下行文本消息，便于断言 WebSocket 协议事件。
     */
    private static final class CapturingWebSocketSession implements WebSocketSession {
        private final String id = UUID.randomUUID().toString();
        private final List<String> sent = new ArrayList<>();

        List<JsonNode> sentEvents() throws IOException {
            List<JsonNode> events = new ArrayList<>();
            for (String message : sent) {
                events.add(JsonUtils.MAPPER.readTree(message));
            }
            return events;
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
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            sent.add(String.valueOf(message.getPayload()));
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() throws IOException {
        }

        @Override
        public void close(CloseStatus status) throws IOException {
        }
    }
}
