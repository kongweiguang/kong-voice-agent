package io.github.kongweiguang.voice.agent.playback;

import io.github.kongweiguang.voice.agent.model.AgentEvent;
import io.github.kongweiguang.voice.agent.model.EventType;
import io.github.kongweiguang.voice.agent.model.payload.ReasonPayload;
import io.github.kongweiguang.voice.agent.model.payload.StateChangedPayload;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.session.TurnLifecycleState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
/**
 * 集中处理播报中的插话打断和客户端主动打断。
 *
 * @author kongweiguang
 */
@Component
@RequiredArgsConstructor
public class InterruptionManager {
    /**
     * 用于下发停止播报、turn 打断和新状态事件。
     */
    private final PlaybackDispatcher dispatcher;

    /**
     * 失效旧 turn、停止播报，并开启新的用户 turn。
     */
    public String interrupt(SessionState session, String reason) {
        String oldTurnId = session.currentTurnId();
        // 先失效旧 turn，再修改播报状态，确保并发回调看到旧 turn 已不可发布。
        session.invalidateTurn(oldTurnId);
        session.interrupted(true);
        session.agentSpeaking(false);
        session.lifecycleState(TurnLifecycleState.INTERRUPTED);
        session.audioEgressAdapter().onPlaybackStop(session, oldTurnId, reason);
        // 前端先停止旧音频，再接收 turn_interrupted，便于清理播放队列和临时状态。
        dispatcher.send(session, AgentEvent.of(EventType.playback_stop, session.sessionId(), oldTurnId, new ReasonPayload(reason)));
        dispatcher.send(session, AgentEvent.of(EventType.turn_interrupted, session.sessionId(), oldTurnId, new ReasonPayload(reason)));
        // 打断后立即创建新 turn，让随后的音频或文本输入有明确归属。
        String newTurnId = session.nextTurnId();
        session.lifecycleState(TurnLifecycleState.USER_PRE_SPEECH);
        dispatcher.send(session, AgentEvent.of(EventType.state_changed, session.sessionId(), newTurnId, new StateChangedPayload(TurnLifecycleState.USER_PRE_SPEECH.name(), null)));
        return newTurnId;
    }
}
