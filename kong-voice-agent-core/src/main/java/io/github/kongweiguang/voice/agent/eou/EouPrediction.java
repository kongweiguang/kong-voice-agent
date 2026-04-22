package io.github.kongweiguang.voice.agent.eou;

/**
 * EOU 判断输出，供端点策略决定提交或继续等待。
 *
 * @param finished 当前用户话语是否已经语义结束
 * @param probability 模型给出的结束概率，兜底实现可返回 1 或 0
 * @param threshold 本次判断使用的阈值
 * @param reason 触发该结果的原因
 * @param modelBacked 是否来自真实模型推理
 * @author kongweiguang
 */
public record EouPrediction(
        boolean finished,
        double probability,
        double threshold,
        String reason,
        boolean modelBacked) {
    /**
     * 归一化概率和原因，保证下游 reason 可直接进入协议事件。
     */
    public EouPrediction {
        probability = Math.max(0.0, Math.min(1.0, probability));
        threshold = Math.max(0.0, Math.min(1.0, threshold));
        reason = reason == null || reason.isBlank() ? (finished ? "eou_detected" : "eou_waiting") : reason.trim();
    }

    /**
     * 创建模型确认结束的预测结果。
     */
    public static EouPrediction detected(double probability, double threshold, boolean modelBacked) {
        return new EouPrediction(true, probability, threshold, "eou_detected", modelBacked);
    }

    /**
     * 创建模型认为用户还会继续说的预测结果。
     */
    public static EouPrediction waiting(double probability, double threshold, boolean modelBacked) {
        return new EouPrediction(false, probability, threshold, "eou_waiting", modelBacked);
    }
}
