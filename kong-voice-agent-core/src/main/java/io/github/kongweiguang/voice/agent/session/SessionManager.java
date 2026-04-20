package io.github.kongweiguang.voice.agent.session;

import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapterFactory;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
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
     * Spring WebSocket id 到语音会话状态的映射。
     */
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    /**
     * 为每个新会话创建独立 ASR 实例的工厂。
     */
    private final StreamingAsrAdapterFactory asrAdapterFactory;

    /**
     * 从外部配置绑定的会话音频格式。
     */
    private final AudioFormatSpec audioFormatSpec;

    /**
     * 为新 WebSocket 连接创建语音会话状态。
     */
    public SessionState create(WebSocketSession webSocketSession) {
        String sessionId = IdUtils.sessionId();
        SessionState state = new SessionState(sessionId, audioFormatSpec, asrAdapterFactory);
        sessions.put(webSocketSession.getId(), state);
        return state;
    }

    /**
     * 查询 WebSocket 连接绑定的语音会话状态。
     */
    public Optional<SessionState> get(WebSocketSession webSocketSession) {
        return Optional.ofNullable(sessions.get(webSocketSession.getId()));
    }

    /**
     * 移除并清理已断开 WebSocket 对应的状态。
     */
    public void destroy(WebSocketSession webSocketSession) {
        SessionState state = sessions.remove(webSocketSession.getId());
        if (state != null) {
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
