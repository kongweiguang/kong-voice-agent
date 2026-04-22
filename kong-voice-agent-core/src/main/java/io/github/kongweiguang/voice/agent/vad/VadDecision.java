package io.github.kongweiguang.voice.agent.vad;

import java.time.Instant;

/**
 * 单个音频窗口的 VAD 结果，包含所属 turn。
 *
 * @author kongweiguang
 */
public record VadDecision(
        /**
         * 当前 VAD 判定所属的 turnId。
         */
        String turnId,

        /**
         * 语音活动概率，范围约定为 0 到 1。
         */
        double speechProbability,

        /**
         * 是否达到说话判定。
         */
        boolean speech,

        /**
         * 当前音频窗口被判定的时间。
         */
        Instant audioAt) {
}
