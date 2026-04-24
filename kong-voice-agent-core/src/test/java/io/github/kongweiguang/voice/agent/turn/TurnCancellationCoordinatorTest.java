package io.github.kongweiguang.voice.agent.turn;

import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.playback.InterruptionManager;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;
import io.github.kongweiguang.voice.agent.tts.TtsOrchestrator;
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
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 turn 取消时能统一释放 ASR 与 TTS 资源。
 *
 * @author kongweiguang
 */
@Tag("turn")
@DisplayName("Turn 取消协调器")
class TurnCancellationCoordinatorTest {
    @Test
    @DisplayName("打断旧 turn 会释放 ASR 与 TTS 缓存")
    void interruptionCancelsAsrAndTtsState() {
        RecordingAsrAdapter asrAdapter = new RecordingAsrAdapter();
        RecordingTtsOrchestrator ttsOrchestrator = new RecordingTtsOrchestrator();
        SessionState session = new SessionState("s1", io.github.kongweiguang.voice.agent.audio.AudioFormatSpec.DEFAULT, (sessionId, format) -> asrAdapter);
        String oldTurnId = session.nextTurnId();
        session.agentSpeaking(true);
        CapturingWebSocketSession ws = new CapturingWebSocketSession();

        new InterruptionManager(new PlaybackDispatcher(), new TurnCancellationCoordinator(ttsOrchestrator))
                .interrupt(session, ws, "barge_in");

        assertThat(asrAdapter.cancelledTurns).contains(oldTurnId);
        assertThat(ttsOrchestrator.cancelledTurns).contains(oldTurnId);
        assertThat(session.isCurrentTurn(oldTurnId)).isFalse();
        assertThat(session.currentTurnId()).isNotEqualTo(oldTurnId);
    }

    /**
     * 记录被取消 turn 的 ASR stub。
     */
    private static final class RecordingAsrAdapter implements StreamingAsrAdapter {
        private final List<String> cancelledTurns = new ArrayList<>();

        @Override
        public Optional<AsrUpdate> acceptAudio(String turnId, byte[] pcm) {
            return Optional.empty();
        }

        @Override
        public AsrUpdate commitTurn(String turnId) {
            return AsrUpdate.finalUpdate(turnId, "");
        }

        @Override
        public void cancelTurn(String turnId) {
            cancelledTurns.add(turnId);
        }

        @Override
        public void close() {
        }
    }

    /**
     * 记录被取消 turn 的 TTS stub。
     */
    private static final class RecordingTtsOrchestrator implements TtsOrchestrator {
        private final List<String> cancelledTurns = new ArrayList<>();

        @Override
        public List<TtsChunk> synthesize(String turnId, Integer startSeq, String text, Boolean lastTextChunk) {
            return List.of();
        }

        @Override
        public void cancelTurn(String turnId) {
            cancelledTurns.add(turnId);
        }
    }

    /**
     * 打断测试只需要 WebSocket 可发送事件，不关心具体载荷。
     */
    private static final class CapturingWebSocketSession implements WebSocketSession {
        private final String id = UUID.randomUUID().toString();

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
