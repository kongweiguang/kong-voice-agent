package io.github.kongweiguang.voice.agent.ws.handler;

import io.github.kongweiguang.voice.agent.service.VoicePipelineService;
import io.github.kongweiguang.voice.agent.ws.WsMessageType;
import io.github.kongweiguang.voice.agent.ws.WsTextMessageContext;
import org.springframework.stereotype.Component;

/**
 * 处理客户端主动打断消息。
 *
 * @author kongweiguang
 */
@Component
public class InterruptWsTextMessageHandler implements WsTextMessageHandler {
    /**
     * 语音流水线服务，负责统一执行打断状态失效逻辑。
     */
    private final VoicePipelineService pipelineService;

    /**
     * 创建主动打断消息处理策略。
     */
    public InterruptWsTextMessageHandler(VoicePipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    /**
     * 返回内置 interrupt 消息类型。
     */
    @Override
    public String type() {
        return WsMessageType.interrupt.name();
    }

    /**
     * 将客户端打断转换为流水线打断操作。
     */
    @Override
    public void handle(WsTextMessageContext context) {
        pipelineService.interrupt(context.sessionState(), context.webSocketSession(), "client_interrupt");
    }
}
