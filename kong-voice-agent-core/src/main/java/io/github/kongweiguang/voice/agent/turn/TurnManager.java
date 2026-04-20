package io.github.kongweiguang.voice.agent.turn;

import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
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
    private final EndpointingPolicy endpointingPolicy;
    /**
     * 每个 session 持有独立 TurnManager，这里只串行化同一连接内的状态机推进。
     */
    private final ReentrantLock lock = new ReentrantLock();
    private Instant speechStartAt;

    /**
     * 根据一次音频/VAD/ASR 更新推进状态机。
     */
    public List<TurnEvent> onAudio(SessionState session, VadDecision vad, Optional<AsrUpdate> asrUpdate, Instant now) {
        lock.lock();
        try {
            List<TurnEvent> events = new ArrayList<>();
            // 插话需要立即暴露给播报层，便于失效旧输出。
            if (session.agentSpeaking() && vad.speech()) {
                events.add(TurnEvent.interrupted(session.currentTurnId(), "barge_in"));
                return events;
            }
            if (vad.speech()) {
                session.lastSpeechAt(now);
            }

            long turnId = session.currentTurnId();
            if (turnId == 0 && vad.speech()) {
                turnId = session.nextTurnId();
                session.activeAsrTurnId(turnId);
            }

            EndpointDecision decision = endpointingPolicy.evaluate(session, vad, now, speechStartAt);
            if (decision.speechStarted()) {
                speechStartAt = now;
                session.lifecycleState(TurnLifecycleState.USER_PRE_SPEECH);
                events.add(TurnEvent.state(session.currentTurnId(), TurnLifecycleState.USER_PRE_SPEECH, decision.reason()));
                session.lifecycleState(TurnLifecycleState.USER_SPEAKING);
                events.add(TurnEvent.state(session.currentTurnId(), TurnLifecycleState.USER_SPEAKING, "speech"));
            } else if (session.lifecycleState() == TurnLifecycleState.USER_SPEAKING && !vad.speech()) {
                session.lifecycleState(TurnLifecycleState.USER_ENDPOINTING);
                events.add(TurnEvent.state(session.currentTurnId(), TurnLifecycleState.USER_ENDPOINTING, "silence_candidate"));
            } else if (session.lifecycleState() == TurnLifecycleState.USER_ENDPOINTING && vad.speech()) {
                session.lifecycleState(TurnLifecycleState.USER_SPEAKING);
                events.add(TurnEvent.state(session.currentTurnId(), TurnLifecycleState.USER_SPEAKING, "speech_resumed"));
            }

            asrUpdate.filter(update -> !update.fin()).ifPresent(update -> session.partialTranscript(update.transcript()));

            // 提交事件是唯一允许 LLM/TTS 启动的边界。
            if (decision.endpointReached() && session.lifecycleState() != TurnLifecycleState.USER_TURN_COMMITTED) {
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
