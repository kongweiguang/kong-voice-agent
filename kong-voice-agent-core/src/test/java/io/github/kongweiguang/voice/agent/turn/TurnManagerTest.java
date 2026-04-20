package io.github.kongweiguang.voice.agent.turn;

import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.session.TurnLifecycleState;
import io.github.kongweiguang.voice.agent.support.TestSessionStates;
import io.github.kongweiguang.voice.agent.vad.VadDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 turn 状态迁移和端点判定边界。
 *
 * @author kongweiguang
 */
@Tag("turn")
@DisplayName("Turn 状态机")
class TurnManagerTest {
    /**
     * LLM 只能在该提交边界产生后启动。
     */
    @Test
    @DisplayName("说话后满足静音条件才提交 turn")
    void commitsOnlyAfterSpeechAndEnoughSilence() {
        SessionState session = TestSessionStates.create("s1");
        TurnManager manager = new TurnManager(new EndpointingPolicy());
        long turnId = session.nextTurnId();
        Instant start = Instant.parse("2026-04-20T00:00:00Z");

        var startEvents = manager.onAudio(session, new VadDecision(turnId, 0.9, true, start), Optional.empty(), start);
        var midEvents = manager.onAudio(session, new VadDecision(turnId, 0.2, false, start.plusMillis(300)), Optional.empty(), start.plusMillis(300));
        var commitEvents = manager.onAudio(session, new VadDecision(turnId, 0.2, false, start.plusMillis(900)), Optional.empty(), start.plusMillis(900));

        assertThat(startEvents).extracting(TurnEvent::state).contains(TurnLifecycleState.USER_PRE_SPEECH, TurnLifecycleState.USER_SPEAKING);
        assertThat(midEvents).noneMatch(TurnEvent::committed);
        assertThat(commitEvents).anyMatch(TurnEvent::committed);
    }

    /**
     * 保护 agent 播报时的插话检测行为。
     */
    @Test
    @DisplayName("Agent 播报中检测到用户说话会报告打断")
    void reportsInterruptionWhenAgentIsSpeakingAndUserStarts() {
        SessionState session = TestSessionStates.create("s1");
        long turnId = session.nextTurnId();
        session.agentSpeaking(true);

        var events = new TurnManager(new EndpointingPolicy()).onAudio(
                session,
                new VadDecision(turnId, 0.9, true, Instant.now()),
                Optional.empty(),
                Instant.now()
        );

        assertThat(events).anyMatch(TurnEvent::interrupted);
    }
}
