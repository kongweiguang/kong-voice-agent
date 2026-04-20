package io.github.kongweiguang.voice.agent.mock.llm;

import io.github.kongweiguang.voice.agent.llm.LlmChunk;
import io.github.kongweiguang.voice.agent.llm.LlmOrchestrator;
import io.github.kongweiguang.voice.agent.llm.LlmRequest;

import java.util.List;
import java.util.function.Consumer;

/**
 * app 模块内的确定性 LLM 模拟实现，用于验证流水线顺序和 turn 防护。
 *
 * @author kongweiguang
 */
public class MockLlmOrchestrator implements LlmOrchestrator {
    /**
     * 输出固定结构的流式回复，便于测试事件顺序和 turnId 保护。
     */
    @Override
    public void stream(LlmRequest request, Consumer<LlmChunk> chunkConsumer) {
        List<String> chunks = List.of(
                "我已收到你的语音内容：",
                request.finalTranscript(),
                "。这是 mock LLM 回复，可以替换为真实模型。"
        );
        for (int i = 0; i < chunks.size(); i++) {
            chunkConsumer.accept(new LlmChunk(request.turnId(), i, chunks.get(i), i == chunks.size() - 1));
        }
    }
}
