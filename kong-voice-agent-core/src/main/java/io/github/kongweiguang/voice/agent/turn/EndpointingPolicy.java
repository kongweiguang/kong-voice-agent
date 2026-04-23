package io.github.kongweiguang.voice.agent.turn;

import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.eou.EouPrediction;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.session.TurnLifecycleState;
import io.github.kongweiguang.voice.agent.vad.VadDecision;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

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
     * EOU 配置，控制语义判断开关和等待窗口。
     */
    private final EouConfig eouConfig;

    /**
     * 使用默认 EOU 配置创建端点策略。
     */
    public EndpointingPolicy() {
        this(new EouConfig(true, null, null, null, true, 0.5, 500, 1600, 300, "zh"));
    }

    /**
     * 使用外部配置创建端点策略。
     */
    public EndpointingPolicy(EouConfig eouConfig) {
        this.eouConfig = eouConfig;
    }

    /**
     * 根据当前 VAD、会话状态和时间窗口判断是否开始或结束用户 turn。
     */
    public EndpointDecision evaluate(SessionState session,
                                     VadDecision vad,
                                     Instant now,
                                     Instant speechStartAt,
                                     Optional<EouPrediction> eouPrediction) {
        // VAD 实现可能直接给 speech 标记，也可能只给概率；二者任一满足即可进入说话判断。
        boolean speech = vad.speechProbability() >= SPEECH_THRESHOLD || Boolean.TRUE.equals(vad.speech());
        if (session.lifecycleState() == TurnLifecycleState.IDLE && speech) {
            return new EndpointDecision(true, false, false, "speech_started");
        }
        if (speechStartAt == null) {
            // 尚未确认说话开始时，不允许仅凭静音触发 endpoint。
            return EndpointDecision.none();
        }
        long speechMs = Duration.between(speechStartAt, now).toMillis();
        if (speechMs >= MAX_TURN_MS) {
            // 最长 turn 是兜底保护，避免用户长时间不断句导致后续 LLM/TTS 永远不启动。
            return new EndpointDecision(false, true, false, "max_turn_ms");
        }
        if (vad.speechProbability() <= SILENCE_THRESHOLD && session.lastSpeechAt() != null) {
            long silenceMs = Duration.between(session.lastSpeechAt(), now).toMillis();
            if (speechMs < MIN_SPEECH_MS) {
                // 过短人声更可能是噪声或误触发，不提交用户 turn。
                return EndpointDecision.none();
            }
            if (!Boolean.TRUE.equals(eouConfig.enabled())) {
                if (silenceMs >= END_SILENCE_MS) {
                    return new EndpointDecision(false, true, false, "end_silence_ms");
                }
                return EndpointDecision.none();
            }
            if (silenceMs >= eouConfig.maxSilenceMs()) {
                // EOU 长时间不确认时按最大静音窗口提交，保证系统不会卡在 endpointing。
                return new EndpointDecision(false, true, false, "eou_max_silence_fallback");
            }
            if (silenceMs >= eouConfig.minSilenceMs()) {
                if (eouPrediction.isPresent()) {
                    EouPrediction prediction = eouPrediction.get();
                    if (Boolean.TRUE.equals(prediction.finished())) {
                        return new EndpointDecision(false, true, false, prediction.reason());
                    }
                    // 模型判断用户可能还会继续说，状态机保持 USER_ENDPOINTING。
                    return EndpointDecision.waiting(prediction.reason());
                }
                if (session.partialTranscript().isBlank() && silenceMs >= END_SILENCE_MS) {
                    // 没有 partial 文本时无法做语义判断，回退到纯静音端点策略。
                    return new EndpointDecision(false, true, false, "end_silence_ms");
                }
                return EndpointDecision.waiting("eou_waiting");
            }
        }
        return EndpointDecision.none();
    }
}
