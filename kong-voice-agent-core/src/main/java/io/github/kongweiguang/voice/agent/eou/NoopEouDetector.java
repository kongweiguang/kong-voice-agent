package io.github.kongweiguang.voice.agent.eou;

/**
 * 不执行语义模型推理的 EOU 兜底实现，用于保持原静音端点行为。
 *
 * @author kongweiguang
 */
public class NoopEouDetector implements EouDetector {
    @Override
    public EouPrediction predict(EouContext context) {
        return new EouPrediction(true, 1.0, 1.0, "eou_unavailable_fallback", false);
    }
}
