package io.github.kongweiguang.voice.agent.eou;

/**
 * 判断当前用户话语是否已经结束的公开扩展点。
 *
 * @author kongweiguang
 */
@FunctionalInterface
public interface EouDetector extends AutoCloseable {
    /**
     * 根据 ASR 文本、静音时长和最近对话历史预测当前 turn 是否可以提交。
     */
    EouPrediction predict(EouContext context);

    /**
     * 默认无资源释放，模型类实现可覆盖。
     */
    @Override
    default void close() {
    }
}
