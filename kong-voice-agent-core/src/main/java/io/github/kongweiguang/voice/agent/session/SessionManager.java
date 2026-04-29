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
     * 语音会话 id 到运行态的主索引，供 WebSocket 控制面和 WebRTC 媒体面共享同一份状态。
     */
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    /**
     * Spring WebSocket id 到语音会话 id 的索引，用于从连接回调快速定位共享会话状态。
     */
    private final ConcurrentMap<String, String> webSocketSessionIds = new ConcurrentHashMap<>();

    /**
     * 语音会话 id 到当前控制面 WebSocket 连接的索引。
     */
    private final ConcurrentMap<String, WebSocketSession> webSocketSessions = new ConcurrentHashMap<>();

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
        String sessionId = webSocketSession.getAttributes().getOrDefault("sessionId", IdUtils.sessionId()).toString();
        SessionState state = create(sessionId);
        String previousWebSocketId = webSocketSessionIds.put(webSocketSession.getId(), sessionId);
        if (previousWebSocketId != null && !previousWebSocketId.equals(sessionId)) {
            webSocketSessions.remove(previousWebSocketId);
        }
        WebSocketSession previousWebSocket = webSocketSessions.put(sessionId, webSocketSession);
        state.bindControlWebSocketSession(webSocketSession);
        state.transportKind(Boolean.TRUE.equals(state.rtcMediaActive()) ? SessionTransportKind.WEBRTC : SessionTransportKind.WS_PCM);
        if (previousWebSocket != null && !previousWebSocket.getId().equals(webSocketSession.getId())) {
            // 同一业务 sessionId 重新绑定控制连接时，只保留最新 WebSocket。
            webSocketSessionIds.remove(previousWebSocket.getId());
        }
        return state;
    }

    /**
     * 为 WebRTC 等独立媒体通道创建或获取共享会话状态。
     */
    public SessionState create(String sessionId) {
        String normalizedSessionId = sessionId == null || sessionId.isBlank() ? IdUtils.sessionId() : sessionId.trim();
        return sessions.computeIfAbsent(normalizedSessionId,
                ignored -> new SessionState(normalizedSessionId, audioFormatSpec, asrAdapterFactory, eouConfig));
    }

    /**
     * 查询 WebSocket 连接绑定的语音会话状态。
     */
    public Optional<SessionState> get(WebSocketSession webSocketSession) {
        String sessionId = webSocketSessionIds.get(webSocketSession.getId());
        return sessionId == null ? Optional.empty() : Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * 通过语音会话 id 查询会话状态，供 WebSocket 回调之外的业务模块复用同一份运行态。
     */
    public Optional<SessionState> get(String sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * 通过语音会话 id 查询当前仍打开的 WebSocket 连接。
     */
    public Optional<WebSocketSession> getWebSocketSession(String sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(webSocketSessions.get(sessionId));
    }

    /**
     * 标记指定会话已经挂载 RTC 媒体链路。
     */
    public SessionState attachRtc(String sessionId) {
        SessionState state = create(sessionId);
        state.rtcMediaActive(true);
        state.transportKind(SessionTransportKind.WEBRTC);
        return state;
    }

    /**
     * 标记指定会话的 RTC 媒体链路已关闭；若控制面也已断开，则一并释放会话状态。
     */
    public void detachRtc(String sessionId) {
        if (sessionId == null) {
            return;
        }
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return;
        }
        state.rtcMediaActive(false);
        state.transportKind(SessionTransportKind.WS_PCM);
        clearIfOrphaned(sessionId, state);
    }

    /**
     * 移除并清理已断开 WebSocket 对应的状态。
     */
    public void destroy(WebSocketSession webSocketSession) {
        String sessionId = webSocketSessionIds.remove(webSocketSession.getId());
        if (sessionId == null) {
            return;
        }
        webSocketSessions.remove(sessionId, webSocketSession);
        SessionState state = sessions.get(sessionId);
        if (state != null) {
            state.unbindControlWebSocketSession(webSocketSession);
            clearIfOrphaned(sessionId, state);
        }
    }

    /**
     * 返回当前仍保持连接的会话数量。
     */
    public int activeCount() {
        return sessions.size();
    }

    /**
     * 当控制面和媒体面都已不存在时释放会话状态。
     */
    private void clearIfOrphaned(String sessionId, SessionState state) {
        if (Boolean.TRUE.equals(state.rtcMediaActive()) || webSocketSessions.containsKey(sessionId)) {
            return;
        }
        if (sessions.remove(sessionId, state)) {
            state.clear();
        }
    }
}
