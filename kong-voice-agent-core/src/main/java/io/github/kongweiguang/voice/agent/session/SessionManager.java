package io.github.kongweiguang.voice.agent.session;

import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapterFactory;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.util.IdUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 将 Spring WebSocket 会话绑定到语音代理会话状态的注册表。
 *
 * @author kongweiguang
 */
@Component
@RequiredArgsConstructor
public class SessionManager {
    /**
     * Spring WebSocket id 到语音会话状态的主索引，保持连接回调和断连清理路径简单稳定。
     */
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    /**
     * Spring WebSocket id 到连接对象的索引，支持异步业务流程按 sessionId 反查当前连接。
     */
    private final ConcurrentMap<String, WebSocketSession> webSocketSessions = new ConcurrentHashMap<>();

    /**
     * 语音会话 id 到 Spring WebSocket id 的辅助索引，供 WebSocket 回调之外的业务模块按 sessionId 查询。
     */
    private final ConcurrentMap<String, String> sessionWebSocketIds = new ConcurrentHashMap<>();

    /**
     * 为每个新会话创建独立 ASR 实例的工厂。
     */
    private final StreamingAsrAdapterFactory asrAdapterFactory;

    /**
     * 从外部配置绑定的会话音频格式。
     */
    private final AudioFormatSpec audioFormatSpec;

    /**
     * EOU 和 endpointing 配置，新 session 会按该配置创建独立状态机。
     */
    private final EouConfig eouConfig;

    /**
     * 为新 WebSocket 连接创建语音会话状态。
     */
    public SessionState create(WebSocketSession webSocketSession) {
        SessionState existing = sessions.get(webSocketSession.getId());
        if (existing != null) {
            return existing;
        }
        String sessionId = webSocketSession.getAttributes().getOrDefault("sessionId", IdUtils.sessionId()).toString();
        SessionState state = new SessionState(sessionId, audioFormatSpec, asrAdapterFactory, eouConfig);
        String previousWebSocketId = sessionWebSocketIds.put(sessionId, webSocketSession.getId());
        if (previousWebSocketId != null && !previousWebSocketId.equals(webSocketSession.getId())) {
            SessionState previous = sessions.remove(previousWebSocketId);
            webSocketSessions.remove(previousWebSocketId);
            if (previous != null) {
                previous.clear();
            }
        }
        sessions.put(webSocketSession.getId(), state);
        webSocketSessions.put(webSocketSession.getId(), webSocketSession);
        return state;
    }

    /**
     * 查询 WebSocket 连接绑定的语音会话状态。
     */
    public Optional<SessionState> get(WebSocketSession webSocketSession) {
        return Optional.ofNullable(sessions.get(webSocketSession.getId()));
    }

    /**
     * 通过语音会话 id 查询会话状态，供 WebSocket 回调之外的业务模块复用同一份运行态。
     */
    public Optional<SessionState> get(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        String webSocketId = sessionWebSocketIds.get(sessionId);
        return webSocketId == null ? Optional.empty() : Optional.ofNullable(sessions.get(webSocketId));
    }

    /**
     * 通过语音会话 id 查询当前仍打开的 WebSocket 连接。
     */
    public Optional<WebSocketSession> getWebSocketSession(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        String webSocketId = sessionWebSocketIds.get(sessionId);
        return webSocketId == null ? Optional.empty() : Optional.ofNullable(webSocketSessions.get(webSocketId));
    }

    /**
     * 移除并清理已断开 WebSocket 对应的状态。
     */
    public void destroy(WebSocketSession webSocketSession) {
        webSocketSessions.remove(webSocketSession.getId());
        SessionState state = sessions.remove(webSocketSession.getId());
        if (state != null) {
            sessionWebSocketIds.remove(state.sessionId(), webSocketSession.getId());
            state.clear();
        }
    }

    /**
     * 返回当前仍保持连接的会话数量。
     */
    public int activeCount() {
        return sessions.size();
    }
}
