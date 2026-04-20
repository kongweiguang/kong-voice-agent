package io.github.kongweiguang.voice.agent.service;

import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.session.TurnLifecycleState;
import io.github.kongweiguang.voice.agent.support.TestSessionStates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证异步回调用到的跨阶段 turnId 安全规则。
 *
 * @author kongweiguang
 */
@Tag("pipeline")
@DisplayName("流水线 turnId 策略")
class VoicePipelinePolicyTest {
    /**
     * 确保打断会在过期 turn 输出发布前将其失效。
     */
    @Test
    @DisplayName("打断后旧 turn 结果不可再发布")
    void sessionRejectsOldTurnAfterInterruption() {
        SessionState session = TestSessionStates.create("s1");
        long oldTurn = session.nextTurnId();
        session.lifecycleState(TurnLifecycleState.AGENT_SPEAKING);
        session.agentSpeaking(true);
        session.invalidateTurn(oldTurn);
        long newTurn = session.nextTurnId();

        AsrUpdate oldFinal = AsrUpdate.finalUpdate(oldTurn, "old");
        AsrUpdate newFinal = AsrUpdate.finalUpdate(newTurn, "new");

        assertThat(session.isCurrentTurn(oldFinal.turnId())).isFalse();
        assertThat(session.isCurrentTurn(newFinal.turnId())).isTrue();
    }
}
