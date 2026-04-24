package io.github.kongweiguang.voice.agent.integration.llm;

import io.github.kongweiguang.voice.agent.llm.LlmChunk;
import io.github.kongweiguang.voice.agent.llm.LlmOrchestrator;
import io.github.kongweiguang.voice.agent.llm.LlmRequest;
import io.github.kongweiguang.voice.agent.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * app 模块内的确定性 LLM 模拟实现，用于验证流水线顺序和 turn 防护。
 *
 * @author kongweiguang
 */
@Component
@RequiredArgsConstructor
public class OllamaLlmOrchestrator implements LlmOrchestrator {
    /**
     * 应用侧 Agent 服务，负责调用底层模型并维护 AgentScope 会话记忆。
     */
    private final AgentService agentService;

    /**
     * 将 AgentService 的 ChatEvent 流转换为核心流水线识别的 LlmChunk。
     */
    @Override
    public void stream(LlmRequest request, Consumer<LlmChunk> chunkConsumer) {
        agentService
                .chat(request.sessionId(), request.finalTranscript())
                .subscribe(event -> {
                    // TEXT 事件会被原样转成 Agent 文本 chunk，后续继续进入 TTS。
                    if ("TEXT".equals(event.getType())) {
                        chunkConsumer.accept(
                                new LlmChunk(
                                        request.turnId(),
                                        0,
                                        event.getContent(),
                                        false,
                                        event.getRawResponse()
                                ));
                    } else if ("COMPLETE".equals(event.getType())) {
                        // COMPLETE 事件只标记本轮 LLM 结束，流水线据此关闭当前 Agent turn。
                        chunkConsumer.accept(
                                new LlmChunk(
                                        request.turnId(),
                                        0,
                                        event.getContent(),
                                        true,
                                        event.getRawResponse()
                                ));
                    }

                });
    }
}
