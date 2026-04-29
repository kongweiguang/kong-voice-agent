package io.github.kongweiguang.voice.agent.playback;

import io.github.kongweiguang.voice.agent.model.AgentEvent;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.util.JsonUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 序列化后端事件，并写入 WebSocket 连接，作为下行发送 Facade 隔离并发发送细节。
 *
 * @author kongweiguang
 */
@Component
public class PlaybackDispatcher {
    /**
     * 每个 WebSocket 连接独立的发送锁，保证异步回调不会并发写同一连接。
     */
    private final ConcurrentMap<String, ReentrantLock> sendLocks = new ConcurrentHashMap<>();

    /**
     * 按会话当前绑定的控制面协议发送事件；核心流水线只关心会话，不直接关心底层连接实现。
     */
    public void send(SessionState sessionState, AgentEvent event) {
        if (sessionState == null) {
            return;
        }
        send(sessionState.controlWebSocketSession(), event);
    }

    /**
     * 基于 WebSocket 会话加锁发送，因为 Spring 会话不保证支持
     * 多个异步回调并发发送消息。
     */
    public void send(WebSocketSession webSocketSession, AgentEvent event) {
        if (webSocketSession == null) {
            return;
        }
        if (!webSocketSession.isOpen()) {
            return;
        }
        try {
            // AgentEvent 是统一下行外壳，序列化后直接作为 WebSocket 文本帧发送给前端。
            String payload = JsonUtils.write(event);
            ReentrantLock lock = sendLocks.computeIfAbsent(webSocketSession.getId(), ignored -> new ReentrantLock());
            lock.lock();
            try {
                if (!webSocketSession.isOpen()) {
                    return;
                }
                // 同一连接的事件按获得发送锁的顺序写出，避免异步 LLM/TTS 回调并发写帧。
                webSocketSession.sendMessage(new TextMessage(payload));
            } finally {
                lock.unlock();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to send websocket event", ex);
        }
    }

    /**
     * 连接关闭后释放发送锁，避免长期运行时累积已断开的 WebSocket id。
     */
    public void release(WebSocketSession webSocketSession) {
        sendLocks.remove(webSocketSession.getId());
    }
}
