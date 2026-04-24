package io.github.kongweiguang.voice.agent.model.payload;

/**
 * Agent 回复文本片段的 payload。
 *
 * @param seq         当前文本片段在本轮回复中的序号，从 0 开始递增
 * @param text        当前下发的 Agent 回复文本片段
 * @param isLast      是否为本轮 Agent 回复文本的最后一个片段
 * @param rawResponse Agent 底层 LLM 返回的原始响应内容，便于联调、审计和问题排查
 * @author kongweiguang
 */
public record AgentTextChunkPayload(Integer seq, String text, Boolean isLast, String rawResponse) implements AgentEventPayload {
    /**
     * 兼容只关心标准文本片段字段的旧调用方，原始响应为空。
     */
    public AgentTextChunkPayload(Integer seq, String text, Boolean isLast) {
        this(seq, text, isLast, null);
    }
}
