package io.github.kongweiguang.voice.agent.model.payload;

/**
 * 无字段事件使用的空 payload 实体。
 *
 * @author kongweiguang
 */
public record EmptyPayload() implements AgentEventPayload {
    /**
     * 无字段 payload 的共享实例，避免重复创建空载荷对象。
     */
    public static final EmptyPayload INSTANCE = new EmptyPayload();
}
