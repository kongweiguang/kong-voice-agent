package io.github.kongweiguang.voice.agent.llm;

/**
 * turn 已提交且 ASR 最终文本存在后发送给 LLM 的请求。
 *
 * @param sessionId       发起 LLM 请求的会话标识
 * @param turnId          已提交用户 turn 的 turnId
 * @param finalTranscript 触发本次 LLM 请求的用户最终文本
 * @author kongweiguang
 */
public record LlmRequest(String sessionId, String turnId, String finalTranscript) {
}
