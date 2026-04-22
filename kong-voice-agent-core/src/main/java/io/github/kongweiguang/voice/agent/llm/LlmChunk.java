package io.github.kongweiguang.voice.agent.llm;

/**
 * 带有 turn 和序号信息的流式 LLM 文本输出。
 *
 * @author kongweiguang
 */
public record LlmChunk(
        /**
         * 当前 LLM 输出所属的 turnId。
         */
        String turnId,

        /**
         * 当前文本片段在本轮回复中的序号。
         */
        int seq,

        /**
         * 当前 LLM 生成的文本片段。
         */
        String text,

        /**
         * 是否为本轮 LLM 回复的最后一个文本片段。
         */
        boolean last) {
}
