package io.github.kongweiguang.voice.agent.ws.handler;

import io.github.kongweiguang.voice.agent.model.AgentEvent;
import io.github.kongweiguang.voice.agent.model.EventType;
import io.github.kongweiguang.voice.agent.model.payload.ErrorPayload;
import io.github.kongweiguang.voice.agent.model.payload.StateChangedPayload;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.service.VoicePipelineService;
import io.github.kongweiguang.voice.agent.session.SessionManager;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.util.JsonUtils;
import io.github.kongweiguang.voice.agent.ws.WsMessage;
import io.github.kongweiguang.voice.agent.ws.WsTextMessageContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * WebSocket 适配器，将客户端帧转换为流水线操作。
 *
 * @author kongweiguang
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWebSocketHandler extends AbstractWebSocketHandler {
    /**
     * 管理 WebSocket 连接和内部语音会话状态的绑定关系。
     */
    private final SessionManager sessionManager;

    /**
     * 承接音频事件的核心流水线服务。
     */
    private final VoicePipelineService pipelineService;

    /**
     * 负责向客户端发送协议事件。
     */
    private final PlaybackDispatcher dispatcher;

    /**
     * JSON 文本消息策略注册表，按 type 分发控制消息和业务自定义消息。
     */
    private final WsTextMessageHandlerRegistry textMessageHandlerRegistry;

    /**
     * 新连接建立后创建会话，并下发初始 IDLE 状态。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SessionState state = sessionManager.create(session);
        log.info("WebSocket connected: ws={}, session={}", session.getId(), state.sessionId());
        dispatcher.send(session, AgentEvent.of(EventType.state_changed, state.sessionId(), 0, new StateChangedPayload("IDLE", "connected")));
    }

    /**
     * 处理 JSON 文本消息；控制消息和用户文本共用该入口，音频载荷使用二进制消息。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SessionState state = sessionManager.get(session).orElseGet(() -> sessionManager.create(session));
        try {
            WsMessage wsMessage = JsonUtils.read(message.getPayload(), WsMessage.class);
            textMessageHandlerRegistry.handle(new WsTextMessageContext(session, state, wsMessage));
        } catch (Exception ex) {
            dispatcher.send(session,
                    AgentEvent.of(
                            EventType.error,
                            state.sessionId(),
                            state.currentTurnId(),
                            new ErrorPayload("bad_message", ex.getMessage()
                            )
                    ));
        }
    }

    /**
     * 复制二进制载荷，因为该回调返回后底层 ByteBuffer 由
     * WebSocket 框架继续持有。
     */
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SessionState state = sessionManager.get(session).orElseGet(() -> sessionManager.create(session));
        byte[] pcm = new byte[message.getPayloadLength()];
        message.getPayload().get(pcm);
        pipelineService.acceptAudio(state, session, pcm);
    }

    /**
     * 连接关闭时释放该连接的所有状态。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionManager.destroy(session);
        dispatcher.release(session);
        log.info("WebSocket closed: ws={}, status={}", session.getId(), status);
    }
}
