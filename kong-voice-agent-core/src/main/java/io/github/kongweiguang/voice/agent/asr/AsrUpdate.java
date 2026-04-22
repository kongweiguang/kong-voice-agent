package io.github.kongweiguang.voice.agent.asr;

import java.time.Instant;

/**
 * 流式 ASR 更新。局部结果用于驱动界面，最终结果用于提交文本。
 *
 * @author kongweiguang
 */
public record AsrUpdate(
        /**
         * 本次 ASR 更新所属的 turnId。
         */
        String turnId,

        /**
         * ASR 当前产出的转写文本。
         */
        String transcript,

        /**
         * 是否为该 turn 的最终转写结果。
         */
        boolean fin,

        /**
         * ASR 更新在服务端生成的时间。
         */
        Instant emittedAt) {
    /**
     * 创建流式局部转写更新。
     */
    public static AsrUpdate partial(String turnId, String transcript) {
        return new AsrUpdate(turnId, transcript, false, Instant.now());
    }

    /**
     * 创建最终转写更新，该更新允许流水线进入 LLM 阶段。
     */
    public static AsrUpdate finalUpdate(String turnId, String transcript) {
        return new AsrUpdate(turnId, transcript, true, Instant.now());
    }
}
