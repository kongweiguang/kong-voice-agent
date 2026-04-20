package io.github.kongweiguang.voice.agent.llm;

/**
 * turn 已提交且 ASR 最终文本存在后发送给 LLM 的请求。
 *
 * @author kongweiguang
 */
public record LlmRequest(
        /**
         * 发起 LLM 请求的会话标识。
         */
        String sessionId,

        /**
         * 已提交用户 turn 的 turnId。
         */
        long turnId,

        /**
         * 触发本次 LLM 请求的用户最终文本。
         */
        String finalTranscript) {
}
