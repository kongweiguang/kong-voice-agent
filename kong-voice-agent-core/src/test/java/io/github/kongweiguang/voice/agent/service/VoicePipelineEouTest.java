package io.github.kongweiguang.voice.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.eou.EouPrediction;
import io.github.kongweiguang.voice.agent.hook.VoicePipelineHook;
import io.github.kongweiguang.voice.agent.llm.LlmChunk;
import io.github.kongweiguang.voice.agent.llm.LlmRequest;
import io.github.kongweiguang.voice.agent.playback.InterruptionManager;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.playback.SessionAudioPlaybackPolicy;
import io.github.kongweiguang.voice.agent.session.SessionManager;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;
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
 * 验证语音流水线在 EOU 判断前不会越过 turn commit 边界。
 *
 * @author kongweiguang
 */
@Tag("pipeline")
@DisplayName("流水线 EOU")
class VoicePipelineEouTest {
    @Test
    @DisplayName("EOU 未完成时不启动 LLM/TTS")
    void doesNotStartLlmWhenEouIsWaiting() throws Exception {
        AtomicInteger llmCalls = new AtomicInteger();
        VoicePipelineService service = serviceWith(EouPrediction.waiting(0.2, 0.5, true), llmCalls);
        SessionState session = session();
        CapturingWebSocketSession ws = new CapturingWebSocketSession();

        service.acceptAudio(session, ws, pcm());
        Thread.sleep(220);
        session.lastSpeechAt(Instant.now().minusMillis(10));
        service.acceptAudio(session, ws, pcm());

        assertThat(llmCalls).hasValue(0);
        assertThat(ws.sentEvents()).extracting(node -> node.get("type").asText()).doesNotContain("asr_final", "agent_thinking");
    }

    @Test
    @DisplayName("EOU 完成后才提交 turn 并启动 LLM")
    void startsLlmAfterEouDetected() throws Exception {
        AtomicInteger llmCalls = new AtomicInteger();
        VoicePipelineService service = serviceWith(EouPrediction.detected(0.8, 0.5, true), llmCalls);
        SessionState session = session();
        CapturingWebSocketSession ws = new CapturingWebSocketSession();

        service.acceptAudio(session, ws, pcm());
        Thread.sleep(220);
        session.lastSpeechAt(Instant.now().minusMillis(10));
        service.acceptAudio(session, ws, pcm());

        assertThat(llmCalls).hasValue(1);
        assertThat(ws.sentEvents()).extracting(node -> node.get("type").asText())
                .containsSubsequence("asr_final", "agent_thinking", "agent_text_chunk", "tts_audio_chunk");
    }

    private VoicePipelineService serviceWith(EouPrediction prediction, AtomicInteger llmCalls) {
        PlaybackDispatcher dispatcher = new PlaybackDispatcher();
        return new VoicePipelineService(
                new SequencedVadEngine(),
                ignored -> prediction,
                eouConfig(),
                (request, consumer) -> replyOnce(request, consumer, llmCalls),
                (turnId, startSeq, text, lastTextChunk) -> List.of(new TtsChunk(turnId, startSeq, lastTextChunk, text.getBytes(StandardCharsets.UTF_8), text)),
                dispatcher,
                new InterruptionManager(dispatcher),
                new SessionAudioPlaybackPolicy(),
                sessionManager(),
                directExecutor(),
                directExecutor(),
                List.<VoicePipelineHook>of()
        );
    }

    private SessionState session() {
        return new SessionState("s1", AudioFormatSpec.DEFAULT, (sessionId, format) -> new PartialAsrAdapter(), eouConfig());
    }

    private void replyOnce(LlmRequest request, Consumer<LlmChunk> consumer, AtomicInteger calls) {
        calls.incrementAndGet();
        consumer.accept(new LlmChunk(request.turnId(), 0, "收到：" + request.finalTranscript(), true));
    }

    private EouConfig eouConfig() {
        return new EouConfig(true, null, null, null, true, 0.5, 1, 50, 300, "zh");
    }

    private Executor directExecutor() {
        return Runnable::run;
    }

    private SessionManager sessionManager() {
        return new SessionManager((sessionId, format) -> new PartialAsrAdapter(),
                AudioFormatSpec.DEFAULT,
                eouConfig());
    }

    private byte[] pcm() {
        return new byte[640];
    }

    /**
     * 第一个窗口为说话，后续窗口为静音，模拟用户短暂停顿。
     */
    private static final class SequencedVadEngine implements VadEngine {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public VadDecision detect(String turnId, byte[] pcm) {
            boolean speech = calls.getAndIncrement() == 0;
            return new VadDecision(turnId, speech ? 0.9 : 0.2, speech, Instant.now());
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
     * 测试专用 ASR，持续产出同一段 partial，并在提交时转为 final。
     */
    private static final class PartialAsrAdapter implements StreamingAsrAdapter {
        @Override
        public java.util.Optional<AsrUpdate> acceptAudio(String turnId, byte[] pcm) {
            return java.util.Optional.of(AsrUpdate.partial(turnId, "我的邮箱是 kong"));
        }

        @Override
        public AsrUpdate commitTurn(String turnId) {
            return AsrUpdate.finalUpdate(turnId, "我的邮箱是 kong");
        }

        @Override
        public void close() {
        }
    }

    /**
     * 收集服务端下行文本消息，便于断言事件顺序。
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
