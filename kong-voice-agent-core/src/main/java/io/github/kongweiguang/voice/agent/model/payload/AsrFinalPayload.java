package io.github.kongweiguang.voice.agent.model.payload;

/**
 * ASR 最终结果或文本直入提交结果。
 *
 * @param text   已提交的用户最终文本
 * @param source 文本来源，音频识别为 audio，文本直入为 text
 * @author kongweiguang
 */
public record AsrFinalPayload(String text, String source) implements AgentEventPayload {
}
