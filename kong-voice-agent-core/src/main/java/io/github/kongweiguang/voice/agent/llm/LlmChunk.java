package io.github.kongweiguang.voice.agent.llm;

/**
 * 带有 turn 和序号信息的流式 LLM 文本输出。
 *
 * @param turnId 当前 LLM 输出所属的 turnId
 * @param seq    当前文本片段在本轮回复中的序号
 * @param text   当前 LLM 生成的文本片段
 * @param last   是否为本轮 LLM 回复的最后一个文本片段
 * @param rawResponse LLM 提供方或上层接入实现返回的原始响应内容，用于 hook、审计和问题排查
 * @author kongweiguang
 */
public record LlmChunk(String turnId, Integer seq, String text, Boolean last, String rawResponse) {
    /**
     * 兼容只关心标准文本片段的旧调用方，原始响应为空。
     */
    public LlmChunk(String turnId, Integer seq, String text, Boolean last) {
        this(turnId, seq, text, last, null);
    }
}
