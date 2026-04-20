package io.github.kongweiguang.voice.agent.turn;

/**
 * 端点判定结果，用于告知 turn 管理器语音是否开始或结束。
 *
 * @author kongweiguang
 */
public record EndpointDecision(
        /**
         * 本次判定是否检测到用户开始说话。
         */
        boolean speechStarted,

        /**
         * 本次判定是否确认用户 turn 已结束。
         */
        boolean endpointReached,

        /**
         * 触发该判定结果的原因。
         */
        String reason) {
    /**
     * 表示本次音频窗口没有产生状态变化。
     */
    public static EndpointDecision none() {
        return new EndpointDecision(false, false, "none");
    }
}
