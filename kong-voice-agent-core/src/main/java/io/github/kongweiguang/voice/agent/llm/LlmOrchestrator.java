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
     *
     * <p>实现必须在方法返回前完成本次请求的所有回调，或通过同步异常暴露失败。
     * 如果底层模型 SDK 使用异步订阅，应在该方法内等待订阅完成，确保流水线能正确收口错误和状态。</p>
     */
    void stream(LlmRequest request, Consumer<LlmChunk> chunkConsumer);
}
