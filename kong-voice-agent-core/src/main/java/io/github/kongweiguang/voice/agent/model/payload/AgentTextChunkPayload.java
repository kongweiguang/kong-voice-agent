package io.github.kongweiguang.voice.agent.model.payload;

/**
 * Agent 回复文本片段的 payload。
 *
 * @param seq    当前文本片段在本轮回复中的序号，从 0 开始递增
 * @param text   当前下发的 Agent 回复文本片段
 * @param isLast 是否为本轮 Agent 回复文本的最后一个片段
 * @author kongweiguang
 */
public record AgentTextChunkPayload(int seq, String text, boolean isLast) implements AgentEventPayload {
}
