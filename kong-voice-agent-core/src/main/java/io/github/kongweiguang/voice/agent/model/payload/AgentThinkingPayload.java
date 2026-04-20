package io.github.kongweiguang.voice.agent.model.payload;

/**
 * Agent 进入思考阶段时携带的用户最终文本。
 *
 * @param transcript 触发 Agent 思考的用户最终文本
 * @author kongweiguang
 */
public record AgentThinkingPayload(String transcript) implements AgentEventPayload {
}
