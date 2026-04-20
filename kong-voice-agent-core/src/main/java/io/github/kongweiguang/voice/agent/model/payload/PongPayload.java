package io.github.kongweiguang.voice.agent.model.payload;

/**
 * ping 心跳响应 payload。
 *
 * @param ok 心跳响应是否成功
 * @author kongweiguang
 */
public record PongPayload(boolean ok) implements AgentEventPayload {
}
