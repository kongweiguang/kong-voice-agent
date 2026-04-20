package io.github.kongweiguang.voice.agent.ws;

import io.github.kongweiguang.voice.agent.session.SessionState;
import org.springframework.web.socket.WebSocketSession;

/**
 * WebSocket JSON 文本消息策略的上下文，集中携带连接、会话状态和协议消息。
 *
 * @param webSocketSession 当前 Spring WebSocket 连接
 * @param sessionState     当前连接绑定的语音会话状态
 * @param message          已解析的上行 JSON 消息
 * @author kongweiguang
 */
public record WsTextMessageContext(
        WebSocketSession webSocketSession,
        SessionState sessionState,
        WsMessage message) {
}
