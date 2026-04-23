package io.github.kongweiguang.voice.agent.vad;

import java.time.Instant;

/**
 * 单个音频窗口的 VAD 结果，包含所属 turn。
 *
 * @param turnId            当前 VAD 判定所属的 turnId
 * @param speechProbability 语音活动概率，范围约定为 0 到 1
 * @param speech            是否达到说话判定
 * @param audioAt           当前音频窗口被判定的时间
 * @author kongweiguang
 */
public record VadDecision(String turnId, Double speechProbability, Boolean speech, Instant audioAt) {
}
