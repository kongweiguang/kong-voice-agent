package io.github.kongweiguang.voice.agent.model.payload;

/**
 * 仅携带文本字段的事件 payload，例如 ASR partial。
 *
 * @param text 当前事件携带的文本内容
 * @author kongweiguang
 */
public record TextPayload(String text) implements AgentEventPayload {
}
