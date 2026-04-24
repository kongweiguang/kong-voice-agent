package io.github.kongweiguang.voice.agent.ws.handler;

import io.github.kongweiguang.voice.agent.service.VoicePipelineService;
import io.github.kongweiguang.voice.agent.ws.WsMessageType;
import io.github.kongweiguang.voice.agent.ws.WsTextMessageContext;
import org.springframework.stereotype.Component;

/**
 * 处理客户端直接提交的文本输入消息。
 *
 * @author kongweiguang
 */
@Component
public class TextWsTextMessageHandler implements WsTextMessageHandler {
    /**
     * 语音流水线服务，负责将文本转为 committed turn 并进入 LLM/TTS。
     */
    private final VoicePipelineService pipelineService;

    /**
     * 创建文本输入消息处理策略。
     */
    public TextWsTextMessageHandler(VoicePipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    /**
     * 返回内置 text 消息类型。
     */
    @Override
    public String type() {
        return WsMessageType.text.name();
    }

    /**
     * 读取 payload.text 并交给流水线处理。
     */
    @Override
    public void handle(WsTextMessageContext context) {
        // 文本消息直接进入 committed turn 路径，后续由流水线统一下发 asr_final 和 Agent 回复。
        pipelineService.acceptText(context.sessionState(), context.webSocketSession(), context.message().textPayload());
    }
}
