package io.github.kongweiguang.voice.agent.turn;

import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.session.TurnLifecycleState;
import io.github.kongweiguang.voice.agent.vad.VadDecision;

import java.time.Duration;
import java.time.Instant;

/**
 * 非抢占式语音代理流程使用的保守端点判定策略。
 *
 * @author kongweiguang
 */
public class EndpointingPolicy {
    /**
     * 判定为说话的默认 VAD 概率阈值。
     */
    public static final double SPEECH_THRESHOLD = 0.6;

    /**
     * 判定为静音候选的默认 VAD 概率阈值。
     */
    public static final double SILENCE_THRESHOLD = 0.35;

    /**
     * 预滚音频保留时长，单位毫秒。
     */
    public static final int PRE_ROLL_MS = 400;

    /**
     * 端点确认需要持续静音的时长，单位毫秒。
     */
    public static final int END_SILENCE_MS = 600;

    /**
     * 最短有效说话时长，避免短噪声直接提交 turn。
     */
    public static final int MIN_SPEECH_MS = 200;

    /**
     * 单个用户 turn 的最长时长，超过后强制提交。
     */
    public static final int MAX_TURN_MS = 15_000;

    /**
     * 根据当前 VAD、会话状态和时间窗口判断是否开始或结束用户 turn。
     */
    public EndpointDecision evaluate(SessionState session, VadDecision vad, Instant now, Instant speechStartAt) {
        boolean speech = vad.speechProbability() >= SPEECH_THRESHOLD || vad.speech();
        if (session.lifecycleState() == TurnLifecycleState.IDLE && speech) {
            return new EndpointDecision(true, false, "speech_started");
        }
        if (speechStartAt == null) {
            return EndpointDecision.none();
        }
        long speechMs = Duration.between(speechStartAt, now).toMillis();
        if (speechMs >= MAX_TURN_MS) {
            return new EndpointDecision(false, true, "max_turn_ms");
        }
        if (vad.speechProbability() <= SILENCE_THRESHOLD && session.lastSpeechAt() != null) {
            long silenceMs = Duration.between(session.lastSpeechAt(), now).toMillis();
            if (speechMs >= MIN_SPEECH_MS && silenceMs >= END_SILENCE_MS) {
                return new EndpointDecision(false, true, "end_silence_ms");
            }
        }
        return EndpointDecision.none();
    }
}
