package io.github.kongweiguang.voice.agent.turn;

import io.github.kongweiguang.voice.agent.session.TurnLifecycleState;

/**
 * TurnManager 产出的状态迁移，供流水线发布或执行动作。
 *
 * @author kongweiguang
 */
public record TurnEvent(
        /**
         * 事件所属的 turnId。
         */
        String turnId,

        /**
         * 事件希望推进到的生命周期状态。
         */
        TurnLifecycleState state,

        /**
         * 是否为用户 turn 提交事件。
         */
        boolean committed,

        /**
         * 是否为打断事件。
         */
        boolean interrupted,

        /**
         * 触发该事件的原因。
         */
        String reason) {
    /**
     * 创建普通状态迁移事件。
     */
    public static TurnEvent state(String turnId, TurnLifecycleState state, String reason) {
        return new TurnEvent(turnId, state, false, false, reason);
    }

    /**
     * 创建用户 turn 提交事件。
     */
    public static TurnEvent committed(String turnId, String reason) {
        return new TurnEvent(turnId, TurnLifecycleState.USER_TURN_COMMITTED, true, false, reason);
    }

    /**
     * 创建旧 turn 被打断的事件。
     */
    public static TurnEvent interrupted(String oldTurnId, String reason) {
        return new TurnEvent(oldTurnId, TurnLifecycleState.INTERRUPTED, false, true, reason);
    }
}
