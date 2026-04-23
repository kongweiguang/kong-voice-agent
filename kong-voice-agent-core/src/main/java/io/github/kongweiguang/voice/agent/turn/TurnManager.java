package io.github.kongweiguang.voice.agent.turn;

import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.eou.EouPrediction;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.session.TurnLifecycleState;
import io.github.kongweiguang.voice.agent.vad.VadDecision;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 负责用户 turn 状态迁移，并产出提交/打断决策，采用 State Machine 模式表达 turn 生命周期。
 *
 * @author kongweiguang
 */
@RequiredArgsConstructor
public class TurnManager {
    /**
     * 端点判定策略，封装 VAD 阈值、静音窗口、最长 turn 和 EOU 等规则。
     */
    private final EndpointingPolicy endpointingPolicy;

    /**
     * 每个 session 持有独立 TurnManager，这里只串行化同一连接内的状态机推进。
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 当前用户 turn 首次确认说话的时间，用于计算最短说话和最长 turn 窗口。
     */
    private Instant speechStartAt;

    /**
     * 根据一次音频/VAD/ASR 更新推进状态机。
     */
    public List<TurnEvent> onAudio(SessionState session,
                                   VadDecision vad,
                                   Optional<AsrUpdate> asrUpdate,
                                   Optional<EouPrediction> eouPrediction,
                                   Instant now) {
        lock.lock();
        try {
            List<TurnEvent> events = new ArrayList<>();
            // 插话需要立即暴露给播报层，便于失效旧输出。
            if (Boolean.TRUE.equals(session.agentSpeaking()) && Boolean.TRUE.equals(vad.speech())) {
                events.add(TurnEvent.interrupted(session.currentTurnId(), "barge_in"));
                return events;
            }
            if (Boolean.TRUE.equals(vad.speech())) {
                session.lastSpeechAt(now);
            }

            String turnId = session.currentTurnId();
            if (turnId == null && Boolean.TRUE.equals(vad.speech())) {
                // 防御性创建 turn：正常路径会在流水线层创建，这里保证状态机单测和未来入口仍安全。
                turnId = session.nextTurnId();
                session.activeAsrTurnId(turnId);
            }

            EndpointDecision decision = endpointingPolicy.evaluate(session, vad, now, speechStartAt, eouPrediction);
            if (Boolean.TRUE.equals(decision.speechStarted())) {
                // 首次进入说话态时连续发出预说话和说话中状态，前端可据此展示录音/识别状态。
                speechStartAt = now;
                session.lifecycleState(TurnLifecycleState.USER_PRE_SPEECH);
                events.add(TurnEvent.state(session.currentTurnId(), TurnLifecycleState.USER_PRE_SPEECH, decision.reason()));
                session.lifecycleState(TurnLifecycleState.USER_SPEAKING);
                events.add(TurnEvent.state(session.currentTurnId(), TurnLifecycleState.USER_SPEAKING, "speech"));
            } else if (session.lifecycleState() == TurnLifecycleState.USER_SPEAKING && !Boolean.TRUE.equals(vad.speech())) {
                // 从说话态进入静音候选，不代表已经提交；EOU 或静音窗口还会继续确认。
                session.lifecycleState(TurnLifecycleState.USER_ENDPOINTING);
                events.add(TurnEvent.state(session.currentTurnId(), TurnLifecycleState.USER_ENDPOINTING, "silence_candidate"));
            } else if (session.lifecycleState() == TurnLifecycleState.USER_ENDPOINTING && Boolean.TRUE.equals(vad.speech())) {
                // 静音候选期间用户继续说话，应回到说话态并继续累计同一个 turn。
                session.lifecycleState(TurnLifecycleState.USER_SPEAKING);
                events.add(TurnEvent.state(session.currentTurnId(), TurnLifecycleState.USER_SPEAKING, "speech_resumed"));
            }

            // partial transcript 写入 session，供 EOU 和前端临时字幕复用；final 只在 commit 阶段写入。
            asrUpdate.filter(update -> !Boolean.TRUE.equals(update.fin())).ifPresent(update -> session.partialTranscript(update.transcript()));

            if (Boolean.TRUE.equals(decision.endpointWaiting()) && session.lifecycleState() == TurnLifecycleState.USER_ENDPOINTING) {
                // 语义 EOU 未确认结束时保持 endpointing，让客户端知道服务端仍在等待补充。
                events.add(TurnEvent.state(session.currentTurnId(), TurnLifecycleState.USER_ENDPOINTING, decision.reason()));
            }

            // 提交事件是唯一允许 LLM/TTS 启动的边界。
            if (Boolean.TRUE.equals(decision.endpointReached()) && session.lifecycleState() != TurnLifecycleState.USER_TURN_COMMITTED) {
                session.lifecycleState(TurnLifecycleState.USER_TURN_COMMITTED);
                events.add(TurnEvent.committed(session.currentTurnId(), decision.reason()));
                speechStartAt = null;
            }
            return events;
        } finally {
            lock.unlock();
        }
    }
}
