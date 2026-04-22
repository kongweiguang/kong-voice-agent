package io.github.kongweiguang.voice.agent.turn;

import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.eou.EouPrediction;
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
        String turnId = session.nextTurnId();
        Instant start = Instant.parse("2026-04-20T00:00:00Z");

        var startEvents = manager.onAudio(session, new VadDecision(turnId, 0.9, true, start), Optional.empty(), Optional.empty(), start);
        var midEvents = manager.onAudio(session, new VadDecision(turnId, 0.2, false, start.plusMillis(300)), Optional.empty(), Optional.empty(), start.plusMillis(300));
        var commitEvents = manager.onAudio(session, new VadDecision(turnId, 0.2, false, start.plusMillis(900)), Optional.empty(), Optional.empty(), start.plusMillis(900));

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
        String turnId = session.nextTurnId();
        session.agentSpeaking(true);

        var events = new TurnManager(new EndpointingPolicy()).onAudio(
                session,
                new VadDecision(turnId, 0.9, true, Instant.now()),
                Optional.empty(),
                Optional.empty(),
                Instant.now()
        );

        assertThat(events).anyMatch(TurnEvent::interrupted);
    }

    /**
     * 保护语义 EOU 对静音候选的提交控制。
     */
    @Test
    @DisplayName("静音候选阶段 EOU 为 true 时提交 turn")
    void commitsWhenEouDetected() {
        SessionState session = TestSessionStates.create("s1");
        TurnManager manager = new TurnManager(new EndpointingPolicy(eouConfig()));
        String turnId = session.nextTurnId();
        Instant start = Instant.parse("2026-04-20T00:00:00Z");
        manager.onAudio(session, new VadDecision(turnId, 0.9, true, start), Optional.empty(), Optional.empty(), start);
        session.partialTranscript("你好，请介绍一下项目");

        var events = manager.onAudio(
                session,
                new VadDecision(turnId, 0.2, false, start.plusMillis(700)),
                Optional.empty(),
                Optional.of(EouPrediction.detected(0.8, 0.5, true)),
                start.plusMillis(700)
        );

        assertThat(events).anyMatch(TurnEvent::committed);
        assertThat(events).anyMatch(event -> "eou_detected".equals(event.reason()));
    }

    /**
     * 保护 EOU 未完成时继续等待用户补充。
     */
    @Test
    @DisplayName("静音候选阶段 EOU 为 false 时不提交 turn")
    void waitsWhenEouNotDetected() {
        SessionState session = TestSessionStates.create("s1");
        TurnManager manager = new TurnManager(new EndpointingPolicy(eouConfig()));
        String turnId = session.nextTurnId();
        Instant start = Instant.parse("2026-04-20T00:00:00Z");
        manager.onAudio(session, new VadDecision(turnId, 0.9, true, start), Optional.empty(), Optional.empty(), start);
        session.partialTranscript("我的邮箱是 kong");

        var events = manager.onAudio(
                session,
                new VadDecision(turnId, 0.2, false, start.plusMillis(700)),
                Optional.empty(),
                Optional.of(EouPrediction.waiting(0.2, 0.5, true)),
                start.plusMillis(700)
        );

        assertThat(events).noneMatch(TurnEvent::committed);
        assertThat(events).anyMatch(event -> "eou_waiting".equals(event.reason()));
    }

    /**
     * 避免模型一直认为未结束时无限等待。
     */
    @Test
    @DisplayName("EOU 未完成但超过最大静音时兜底提交")
    void commitsWhenMaxSilenceReached() {
        SessionState session = TestSessionStates.create("s1");
        TurnManager manager = new TurnManager(new EndpointingPolicy(eouConfig()));
        String turnId = session.nextTurnId();
        Instant start = Instant.parse("2026-04-20T00:00:00Z");
        manager.onAudio(session, new VadDecision(turnId, 0.9, true, start), Optional.empty(), Optional.empty(), start);
        session.partialTranscript("我的邮箱是 kong");

        var events = manager.onAudio(
                session,
                new VadDecision(turnId, 0.2, false, start.plusMillis(1800)),
                Optional.empty(),
                Optional.of(EouPrediction.waiting(0.2, 0.5, true)),
                start.plusMillis(1800)
        );

        assertThat(events).anyMatch(TurnEvent::committed);
        assertThat(events).anyMatch(event -> "eou_max_silence_fallback".equals(event.reason()));
    }

    private EouConfig eouConfig() {
        return new EouConfig(true, null, null, null, true, 0.5, 500, 1600, 300, "zh");
    }
}
