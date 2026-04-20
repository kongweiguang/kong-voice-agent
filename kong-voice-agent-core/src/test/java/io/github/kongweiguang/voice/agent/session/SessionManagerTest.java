package io.github.kongweiguang.voice.agent.session;

import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.support.NoopStreamingAsrAdapter;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在不启动服务的情况下验证 WebSocket 会话生命周期行为。
 *
 * @author kongweiguang
 */
@Tag("session")
@DisplayName("Session 生命周期")
class SessionManagerTest {
    /**
     * 保护独立会话分配和断连清理行为。
     */
    @Test
    @DisplayName("创建独立 session 并在断连时清理")
    void createsIndependentSessionsAndDestroysThem() {
        SessionManager manager = new SessionManager(
                (sessionId, format) -> new NoopStreamingAsrAdapter(),
                AudioFormatSpec.DEFAULT
        );
        WebSocketSession ws1 = new StubWebSocketSession();
        WebSocketSession ws2 = new StubWebSocketSession();

        SessionState first = manager.create(ws1);
        SessionState second = manager.create(ws2);

        assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
        assertThat(first.nextTurnId()).isEqualTo(1);
        assertThat(first.nextTurnId()).isEqualTo(2);
        assertThat(manager.activeCount()).isEqualTo(2);

        manager.destroy(ws1);

        assertThat(manager.get(ws1)).isEmpty();
        assertThat(manager.activeCount()).isEqualTo(1);
    }

    /**
     * 保护会话创建时使用外部配置绑定后的音频格式，而不是重新落回硬编码默认值。
     */
    @Test
    @DisplayName("创建 session 时使用配置化音频格式")
    void createsSessionWithConfiguredAudioFormat() {
        AudioFormatSpec configuredFormat = new AudioFormatSpec(8000, 2, "s16le", 40);
        AtomicReference<AudioFormatSpec> asrFormat = new AtomicReference<>();
        SessionManager manager = new SessionManager((sessionId, format) -> {
            asrFormat.set(format);
            return new NoopStreamingAsrAdapter();
        }, configuredFormat);

        manager.create(new StubWebSocketSession());

        assertThat(asrFormat.get()).isEqualTo(configuredFormat);
    }

    /**
     * 管理器测试使用的最小 WebSocketSession 实现。
     */
    private static final class StubWebSocketSession implements WebSocketSession {
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
