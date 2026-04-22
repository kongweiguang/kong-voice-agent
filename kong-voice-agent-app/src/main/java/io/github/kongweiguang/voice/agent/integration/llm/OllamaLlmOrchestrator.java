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
    private final AgentService agentService;

    /**
     * 输出固定结构的流式回复，便于测试事件顺序和 turnId 保护。
     */
    @Override
    public void stream(LlmRequest request, Consumer<LlmChunk> chunkConsumer) {
        agentService
                .chat(request.sessionId(), request.finalTranscript())
                .subscribe(event -> {
                    System.out.println(event.getType() + " --> :" + event.getContent());

                    if (event.getType() == "TEXT") {
                        chunkConsumer.accept(
                                new LlmChunk(
                                        request.turnId(),
                                        0,
                                        event.getContent(),
                                        false
                                ));
                    } else if (event.getType() == "COMPLETE") {
                        chunkConsumer.accept(
                                new LlmChunk(
                                        request.turnId(),
                                        0,
                                        event.getContent(),
                                        true
                                ));
                    }

                });
    }
}
