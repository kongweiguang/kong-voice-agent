package io.github.kongweiguang.voice.agent.turn;

/**
 * 端点判定结果，用于告知 turn 管理器语音是否开始或结束。
 *
 * @param speechStarted   本次判定是否检测到用户开始说话
 * @param endpointReached 本次判定是否确认用户 turn 已结束
 * @param endpointWaiting 本次判定是否仍在等待语义 EOU 确认
 * @param reason          触发该判定结果的原因
 * @author kongweiguang
 */
public record EndpointDecision(Boolean speechStarted,
                               Boolean endpointReached,
                               Boolean endpointWaiting,
                               String reason) {
    /**
     * 表示本次音频窗口没有产生状态变化。
     */
    public static EndpointDecision none() {
        return new EndpointDecision(false, false, false, "none");
    }

    /**
     * 表示已经进入静音候选，但语义 EOU 尚未确认结束。
     */
    public static EndpointDecision waiting(String reason) {
        return new EndpointDecision(false, false, true, reason);
    }
}
