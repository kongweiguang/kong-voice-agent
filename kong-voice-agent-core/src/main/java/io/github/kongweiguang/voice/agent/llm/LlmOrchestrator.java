package io.github.kongweiguang.voice.agent.llm;

import java.util.function.Consumer;

/**
 * 可替换的 LLM 边界，采用 Strategy/Adapter 模式隔离真实模型供应商差异。
 * 实现只能在 turn 提交后被调用。
 *
 * @author kongweiguang
 */
public interface LlmOrchestrator {
    /**
     * 将响应片段流式传给指定消费者。
     */
    void stream(LlmRequest request, Consumer<LlmChunk> chunkConsumer);
}
