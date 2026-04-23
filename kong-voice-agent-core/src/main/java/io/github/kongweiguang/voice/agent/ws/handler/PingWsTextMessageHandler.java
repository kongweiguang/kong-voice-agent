package io.github.kongweiguang.voice.agent.ws.handler;

import io.github.kongweiguang.voice.agent.model.AgentEvent;
import io.github.kongweiguang.voice.agent.model.EventType;
import io.github.kongweiguang.voice.agent.model.payload.PongPayload;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.ws.WsMessageType;
import io.github.kongweiguang.voice.agent.ws.WsTextMessageContext;
import org.springframework.stereotype.Component;

/**
 * 处理客户端心跳消息，并返回 pong 下行事件。
 *
 * @author kongweiguang
 */
@Component
public class PingWsTextMessageHandler implements WsTextMessageHandler {
    /**
     * WebSocket 下行事件发送器。
     */
    private final PlaybackDispatcher dispatcher;

    /**
     * 创建心跳消息处理策略。
     */
    public PingWsTextMessageHandler(PlaybackDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 返回内置 ping 消息类型。
     */
    @Override
    public String type() {
        return WsMessageType.ping.name();
    }

    /**
     * 发送携带当前 turnId 的 pong 事件。
     */
    @Override
    public void handle(WsTextMessageContext context) {
        // 心跳不改变业务状态，只把当前 sessionId/turnId 带回客户端用于连接保活和调试。
        dispatcher.send(context.webSocketSession(), AgentEvent.of(EventType.pong,
                context.sessionState().sessionId(),
                context.sessionState().currentTurnId(),
                new PongPayload(true)));
    }
}
