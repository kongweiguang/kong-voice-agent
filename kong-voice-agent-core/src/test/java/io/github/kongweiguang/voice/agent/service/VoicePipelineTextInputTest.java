package io.github.kongweiguang.voice.agent.service;

import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.support.NoopStreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.eou.NoopEouDetector;
import io.github.kongweiguang.voice.agent.support.TestSessionStates;
import io.github.kongweiguang.voice.agent.hook.VoicePipelineHook;
import io.github.kongweiguang.voice.agent.llm.LlmChunk;
import io.github.kongweiguang.voice.agent.llm.LlmRequest;
import io.github.kongweiguang.voice.agent.playback.InterruptionManager;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.playback.SessionAudioPlaybackPolicy;
import io.github.kongweiguang.voice.agent.session.SessionManager;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;
import io.github.kongweiguang.voice.agent.vad.VadDecision;
import io.github.kongweiguang.voice.agent.vad.VadEngine;
import io.github.kongweiguang.v1.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证客户端直接提交文本时，可以跳过 ASR 并复用 LLM/TTS 闭环。
 *
 * @author kongweiguang
 */
@Tag("pipeline")
@DisplayName("文本输入流水线")
class VoicePipelineTextInputTest {
    @Test
    @DisplayName("文本输入创建 committed turn 并进入 LLM/TTS")
    void acceptsTextAsCommittedTurn() throws Exception {
        PlaybackDispatcher dispatcher = new PlaybackDispatcher();
        CapturingHook hook = new CapturingHook();
        VoicePipelineService service = new VoicePipelineService(
                new NoopVadEngine(),
                new NoopEouDetector(),
                eouConfig(),
                this::replyOnce,
                (turnId, startSeq, text, lastTextChunk) -> List.of(new TtsChunk(turnId, startSeq, lastTextChunk, text.getBytes(StandardCharsets.UTF_8), text)),
                dispatcher,
                new InterruptionManager(dispatcher),
                new SessionAudioPlaybackPolicy(),
                sessionManager(),
                directExecutor(),
                directExecutor(),
                List.of(hook)
        );
        SessionState session = TestSessionStates.create("s1");
        CapturingWebSocketSession ws = new CapturingWebSocketSession();

        service.acceptText(session, ws, "  你好  ");

        List<JsonNode> events = ws.sentEvents();
        assertThat(events).extracting(node -> node.get("type").asText())
                .containsSubsequence("state_changed", "asr_final", "agent_thinking", "agent_text_chunk", "tts_audio_chunk");
        JsonNode finalText = findEvent(events, "asr_final");
        assertThat(finalText.at("/payload/text").asText()).isEqualTo("你好");
        assertThat(finalText.at("/payload/source").asText()).isEqualTo("text");
        assertThat(session.finalTranscript()).isEqualTo("你好");
        assertThat(hook.events).containsSubsequence("text:你好", "commit:text", "before_llm", "llm_chunk", "tts_chunk");
    }

    /**
     * 空白文本不能创建 committed turn，避免前端误触发空请求污染会话状态。
     */
    @Test
    @DisplayName("空白文本会被拒绝且不会创建 turn")
    void rejectsBlankText() {
        PlaybackDispatcher dispatcher = new PlaybackDispatcher();
        VoicePipelineService service = new VoicePipelineService(
                new NoopVadEngine(),
                new NoopEouDetector(),
                eouConfig(),
                this::replyOnce,
                (turnId, startSeq, text, lastTextChunk) -> List.of(new TtsChunk(turnId, startSeq, lastTextChunk, text.getBytes(StandardCharsets.UTF_8), text)),
                dispatcher,
                new InterruptionManager(dispatcher),
                new SessionAudioPlaybackPolicy(),
                sessionManager(),
                directExecutor(),
                directExecutor(),
                List.of()
        );
        SessionState session = TestSessionStates.create("s1");
        CapturingWebSocketSession ws = new CapturingWebSocketSession();

        assertThatThrownBy(() -> service.acceptText(session, ws, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("payload.text must not be blank");
        assertThat(session.currentTurnId()).isNull();
    }

    /**
     * TTS 位于 LLM 流式回调内，异常必须转成协议 error，不能向订阅线程继续冒泡。
     */
    @Test
    @DisplayName("TTS 异常会转成 error 事件并结束本轮播报状态")
    void publishesErrorWhenTtsFails() throws Exception {
        PlaybackDispatcher dispatcher = new PlaybackDispatcher();
        VoicePipelineService service = new VoicePipelineService(
                new NoopVadEngine(),
                new NoopEouDetector(),
                eouConfig(),
                this::replyOnce,
                (turnId, startSeq, text, lastTextChunk) -> {
                    throw new IllegalStateException("Kokoro TTS 返回了空音频");
                },
                dispatcher,
                new InterruptionManager(dispatcher),
                new SessionAudioPlaybackPolicy(),
                sessionManager(),
                directExecutor(),
                directExecutor(),
                List.of()
        );
        SessionState session = TestSessionStates.create("s1");
        CapturingWebSocketSession ws = new CapturingWebSocketSession();

        service.acceptText(session, ws, "你好");

        List<JsonNode> events = ws.sentEvents();
        assertThat(events).extracting(node -> node.get("type").asText())
                .containsSubsequence("agent_text_chunk", "error");
        JsonNode error = findEvent(events, "error");
        assertThat(error.at("/payload/code").asText()).isEqualTo("tts_failed");
        assertThat(error.at("/payload/message").asText()).contains("Kokoro TTS 返回了空音频");
        assertThat(session.agentSpeaking()).isFalse();
    }

    /**
     * 某些流式 LLM 会用空片段表达完成信号，流水线应只收口状态，不触发 TTS。
     */
    @Test
    @DisplayName("空的 LLM 完成片段不会触发 TTS")
    void ignoresBlankLlmCompletionChunk() throws Exception {
        PlaybackDispatcher dispatcher = new PlaybackDispatcher();
        VoicePipelineService service = new VoicePipelineService(
                new NoopVadEngine(),
                new NoopEouDetector(),
                eouConfig(),
                (request, consumer) -> consumer.accept(new LlmChunk(request.turnId(), 0, null, true)),
                (turnId, startSeq, text, lastTextChunk) -> {
                    throw new AssertionError("空完成片段不应该触发 TTS");
                },
                dispatcher,
                new InterruptionManager(dispatcher),
                new SessionAudioPlaybackPolicy(),
                sessionManager(),
                directExecutor(),
                directExecutor(),
                List.of()
        );
        SessionState session = TestSessionStates.create("s1");
        CapturingWebSocketSession ws = new CapturingWebSocketSession();

        service.acceptText(session, ws, "你好");

        List<JsonNode> events = ws.sentEvents();
        assertThat(events).extracting(node -> node.get("type").asText())
                .containsSubsequence("state_changed", "asr_final", "agent_thinking");
        assertThat(events).noneMatch(node -> "tts_audio_chunk".equals(node.get("type").asText()));
        assertThat(session.agentSpeaking()).isFalse();
    }

    @Test
    @DisplayName("LLM 文本片段会直接触发 TTS")
    void sendsEachLlmTextChunkToTts() throws Exception {
        PlaybackDispatcher dispatcher = new PlaybackDispatcher();
        List<String> synthesizedTexts = new ArrayList<>();
        VoicePipelineService service = new VoicePipelineService(
                new NoopVadEngine(),
                new NoopEouDetector(),
                eouConfig(),
                (request, consumer) -> {
                    consumer.accept(new LlmChunk(request.turnId(), 0, "Hello", false));
                    consumer.accept(new LlmChunk(request.turnId(), 1, " ", false));
                    consumer.accept(new LlmChunk(request.turnId(), 2, "world", false));
                    consumer.accept(new LlmChunk(request.turnId(), 3, ".", true));
                },
                (turnId, startSeq, text, lastTextChunk) -> {
                    synthesizedTexts.add(text);
                    return List.of(new TtsChunk(turnId, startSeq, lastTextChunk, text.getBytes(StandardCharsets.UTF_8), text));
                },
                dispatcher,
                new InterruptionManager(dispatcher),
                new SessionAudioPlaybackPolicy(),
                sessionManager(),
                directExecutor(),
                directExecutor(),
                List.of()
        );
        SessionState session = TestSessionStates.create("s1");
        CapturingWebSocketSession ws = new CapturingWebSocketSession();

        service.acceptText(session, ws, "hello");

        List<JsonNode> events = ws.sentEvents();
        assertThat(events).extracting(node -> node.get("type").asText())
                .containsSubsequence("agent_text_chunk", "agent_text_chunk", "agent_text_chunk", "tts_audio_chunk");
        assertThat(synthesizedTexts).containsExactly("Hello", " ", "world", ".");
        assertThat(events.stream().filter(node -> "tts_audio_chunk".equals(node.get("type").asText()))).hasSize(4);
    }

    private void replyOnce(LlmRequest request, Consumer<LlmChunk> consumer) {
        consumer.accept(new LlmChunk(request.turnId(), 0, "收到：" + request.finalTranscript(), true));
    }

    private Executor directExecutor() {
        return Runnable::run;
    }

    private SessionManager sessionManager() {
        return new SessionManager((sessionId, format) -> new NoopStreamingAsrAdapter(),
                AudioFormatSpec.DEFAULT,
                eouConfig());
    }

    private EouConfig eouConfig() {
        return new EouConfig(true, null, null, null, true, 0.5, 500, 1600, 300, "zh");
    }

    private JsonNode findEvent(List<JsonNode> events, String type) {
        return events.stream()
                .filter(node -> type.equals(node.get("type").asText()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * 验证业务 hook 能观察文本、提交、LLM 与 TTS 的关键节点。
     */
    private static final class CapturingHook implements VoicePipelineHook {
        private final List<String> events = new ArrayList<>();

        @Override
        public void onTextReceived(SessionState session, WebSocketSession ws, String text) {
            events.add("text:" + text);
        }

        @Override
        public void onTurnCommitted(SessionState session, WebSocketSession ws, AsrUpdate finalAsr, String source) {
            events.add("commit:" + source);
        }

        @Override
        public void beforeLlm(SessionState session, WebSocketSession ws, LlmRequest request) {
            events.add("before_llm");
        }

        @Override
        public void onLlmChunk(SessionState session, WebSocketSession ws, LlmChunk chunk) {
            events.add("llm_chunk");
        }

        @Override
        public void onTtsChunk(SessionState session, WebSocketSession ws, TtsChunk chunk) {
            events.add("tts_chunk");
        }
    }

    /**
     * 文本输入测试不会触发 VAD，该实现只满足构造依赖。
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
                events.add(Json.node(message));
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
