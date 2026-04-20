package io.github.kongweiguang.voice.agent.model.payload;

/**
 * 打断、停止等原因类事件的通用 payload。
 *
 * @param reason 触发打断、停止或状态变化的原因
 * @author kongweiguang
 */
public record ReasonPayload(String reason) implements AgentEventPayload {
}
