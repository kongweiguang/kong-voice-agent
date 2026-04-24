package io.github.kongweiguang.voice.agent.playback;

import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.support.TestSessionStates;
import io.github.kongweiguang.voice.agent.tts.TtsOrchestrator;
import io.github.kongweiguang.voice.agent.turn.TurnCancellationCoordinator;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证主动打断的边界行为。
 *
 * @author kongweiguang
 */
@Tag("pipeline")
@DisplayName("打断管理器")
class InterruptionManagerTest {
    @Test
    @DisplayName("空闲状态 interrupt 不创建新 turn")
    void idleInterruptIsNoop() {
        SessionState session = TestSessionStates.create("s1");
        CapturingWebSocketSession ws = new CapturingWebSocketSession();

        String newTurnId = new InterruptionManager(new PlaybackDispatcher(), new TurnCancellationCoordinator(noopTtsOrchestrator()))
                .interrupt(session, ws, "client_interrupt");

        assertThat(newTurnId).isNull();
        assertThat(session.currentTurnId()).isNull();
        assertThat(ws.sent).isEmpty();
    }

    private TtsOrchestrator noopTtsOrchestrator() {
        return (turnId, startSeq, text, lastTextChunk) -> List.of();
    }

    /**
     * 收集打断事件，便于验证 no-op 时不会下发状态。
     */
    private static final class CapturingWebSocketSession implements WebSocketSession {
        private final String id = UUID.randomUUID().toString();
        private final List<String> sent = new ArrayList<>();

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
