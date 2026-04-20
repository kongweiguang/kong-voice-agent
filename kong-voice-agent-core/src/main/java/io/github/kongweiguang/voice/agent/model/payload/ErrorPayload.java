package io.github.kongweiguang.voice.agent.model.payload;

/**
 * 协议级失败使用的标准错误体。
 *
 * @param code    错误码，用于客户端按类型处理错误
 * @param message 面向调试和日志展示的错误说明
 * @author kongweiguang
 */
public record ErrorPayload(String code, String message) implements AgentEventPayload {
}
