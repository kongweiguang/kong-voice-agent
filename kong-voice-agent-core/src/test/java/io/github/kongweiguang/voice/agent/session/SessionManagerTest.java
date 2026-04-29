package io.github.kongweiguang.voice.agent.session;

import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
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
import java.util.concurrent.ConcurrentHashMap;
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
                AudioFormatSpec.DEFAULT,
                eouConfig()
        );
        WebSocketSession ws1 = new StubWebSocketSession();
        WebSocketSession ws2 = new StubWebSocketSession();

        SessionState first = manager.create(ws1);
        SessionState second = manager.create(ws2);

        assertThat(first.sessionId()).startsWith("sess_");
        assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
        assertThat(manager.get(first.sessionId())).containsSame(first);
        assertThat(manager.getWebSocketSession(first.sessionId())).containsSame(ws1);
        assertThat(manager.get(ws1)).containsSame(first);
        String firstTurnId = first.nextTurnId();
        String secondTurnId = first.nextTurnId();
        assertThat(firstTurnId).containsOnlyDigits();
        assertThat(secondTurnId).containsOnlyDigits().isNotEqualTo(firstTurnId);
        assertThat(first.isCurrentTurn(firstTurnId)).isFalse();
        assertThat(first.isCurrentTurn(secondTurnId)).isTrue();
        assertThat(manager.activeCount()).isEqualTo(2);

        manager.destroy(ws1);

        assertThat(manager.get(ws1)).isEmpty();
        assertThat(manager.get(first.sessionId())).isEmpty();
        assertThat(manager.getWebSocketSession(first.sessionId())).isEmpty();
        assertThat(manager.activeCount()).isEqualTo(1);
    }

    /**
     * 保护握手阶段写入的 sessionId 能建立辅助索引，方便其他业务按该 id 找回 WebSocket 连接。
     */
    @Test
    @DisplayName("使用握手属性中的 sessionId 建立查询索引")
    void usesHandshakeSessionIdAsLookupIndex() {
        SessionManager manager = new SessionManager(
                (sessionId, format) -> new NoopStreamingAsrAdapter(),
                AudioFormatSpec.DEFAULT,
                eouConfig()
        );
        WebSocketSession ws = new StubWebSocketSession(Map.of("sessionId", "sess_custom"));

        SessionState state = manager.create(ws);

        assertThat(state.sessionId()).isEqualTo("sess_custom");
        assertThat(manager.get("sess_custom")).containsSame(state);
        assertThat(manager.getWebSocketSession("sess_custom")).containsSame(ws);
    }

    /**
     * 保护同一业务 sessionId 重连时的新旧连接切换，避免旧连接稍后断开时误删新连接索引。
     */
    @Test
    @DisplayName("同 sessionId 重连后旧连接断开不会误删新连接")
    void keepsNewestConnectionWhenSameSessionReconnects() {
        SessionManager manager = new SessionManager(
                (sessionId, format) -> new NoopStreamingAsrAdapter(),
                AudioFormatSpec.DEFAULT,
                eouConfig()
        );
        WebSocketSession oldWs = new StubWebSocketSession(Map.of("sessionId", "sess_reconnect"));
        WebSocketSession newWs = new StubWebSocketSession(Map.of("sessionId", "sess_reconnect"));

        SessionState oldState = manager.create(oldWs);
        String oldTurnId = oldState.nextTurnId();
        SessionState newState = manager.create(newWs);

        assertThat(newState.sessionId()).isEqualTo("sess_reconnect");
        assertThat(newState).isSameAs(oldState);
        assertThat(oldState.isCurrentTurn(oldTurnId)).isTrue();
        assertThat(manager.get("sess_reconnect")).containsSame(newState);
        assertThat(manager.getWebSocketSession("sess_reconnect")).containsSame(newWs);
        assertThat(manager.get(oldWs)).isEmpty();
        assertThat(manager.activeCount()).isEqualTo(1);

        manager.destroy(oldWs);

        assertThat(manager.get("sess_reconnect")).containsSame(newState);
        assertThat(manager.getWebSocketSession("sess_reconnect")).containsSame(newWs);
        assertThat(manager.get(newWs)).containsSame(newState);
        assertThat(manager.activeCount()).isEqualTo(1);
    }

    /**
     * 保护 WebRTC 媒体面仍在线时，控制面断开不会提前清理共享 session。
     */
    @Test
    @DisplayName("RTC 媒体仍活跃时控制面断开不清理 session")
    void keepsSessionWhileRtcIsStillAttached() {
        SessionManager manager = new SessionManager(
                (sessionId, format) -> new NoopStreamingAsrAdapter(),
                AudioFormatSpec.DEFAULT,
                eouConfig()
        );
        WebSocketSession ws = new StubWebSocketSession(Map.of("sessionId", "sess_rtc"));

        SessionState state = manager.create(ws);
        manager.attachRtc("sess_rtc");

        manager.destroy(ws);

        assertThat(manager.get("sess_rtc")).containsSame(state);
        assertThat(manager.getWebSocketSession("sess_rtc")).isEmpty();
        assertThat(state.rtcMediaActive()).isTrue();
        assertThat(manager.activeCount()).isEqualTo(1);

        manager.detachRtc("sess_rtc");

        assertThat(manager.get("sess_rtc")).isEmpty();
        assertThat(manager.activeCount()).isZero();
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
        }, configuredFormat, eouConfig());

        manager.create(new StubWebSocketSession());

        assertThat(asrFormat.get()).isEqualTo(configuredFormat);
    }

    /**
     * 管理器测试使用的最小 WebSocketSession 实现。
     */
    private static final class StubWebSocketSession implements WebSocketSession {
        private final String id = UUID.randomUUID().toString();
        private final Map<String, Object> attributes;

        private StubWebSocketSession() {
            this(Map.of());
        }

        private StubWebSocketSession(Map<String, Object> attributes) {
            this.attributes = new ConcurrentHashMap<>(attributes);
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
            return attributes;
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

    private EouConfig eouConfig() {
        return new EouConfig(true, null, null, null, true, 0.5, 500, 1600, 300, "zh");
    }
}
