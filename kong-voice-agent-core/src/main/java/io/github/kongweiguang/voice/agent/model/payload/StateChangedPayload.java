package io.github.kongweiguang.voice.agent.model.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 会话或 turn 状态变化 payload。
 *
 * @param state  变化后的会话或 turn 生命周期状态
 * @param reason 触发状态变化的原因，允许为空
 * @author kongweiguang
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StateChangedPayload(String state, String reason) implements AgentEventPayload {
}
