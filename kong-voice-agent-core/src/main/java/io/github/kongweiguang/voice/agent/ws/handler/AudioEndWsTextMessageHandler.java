package io.github.kongweiguang.voice.agent.ws.handler;

import io.github.kongweiguang.voice.agent.service.VoicePipelineService;
import io.github.kongweiguang.voice.agent.ws.WsMessageType;
import io.github.kongweiguang.voice.agent.ws.WsTextMessageContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 处理客户端音频结束消息，推动当前音频 turn 尝试提交。
 *
 * @author kongweiguang
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AudioEndWsTextMessageHandler implements WsTextMessageHandler {
    /**
     * 语音流水线服务，负责 ASR final 和 LLM/TTS 后续编排。
     */
    private final VoicePipelineService pipelineService;

    /**
     * 返回内置 audio_end 消息类型。
     */
    @Override
    public String type() {
        return WsMessageType.audio_end.name();
    }

    /**
     * 将音频结束控制消息提交给流水线处理。
     */
    @Override
    public void handle(WsTextMessageContext context) {
        // audio_end 是客户端声明本轮音频结束的控制帧，流水线会立即尝试提交 ASR final。
        log.info("Handle audio_end: ws={}, sessionId={}, turnId={}",
                context.webSocketSession().getId(), context.sessionState().sessionId(), context.sessionState().currentTurnId());
        pipelineService.commitAudioEnd(context.sessionState(), context.webSocketSession());
    }
}
