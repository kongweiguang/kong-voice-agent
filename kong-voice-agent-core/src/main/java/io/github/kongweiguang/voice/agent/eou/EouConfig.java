package io.github.kongweiguang.voice.agent.eou;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * EOU 模型、阈值和 endpointing 等待窗口的外部配置。
 *
 * @param enabled 是否启用语义 EOU；关闭后回到原始静音端点策略
 * @param provider 默认 EOU 提供方标识，当前内置 livekit-multilingual
 * @param modelPath LiveKit turn detector ONNX 模型路径
 * @param tokenizerPath Hugging Face tokenizer.json 路径
 * @param fallbackEnabled 模型不可用时是否允许降级为静音兜底
 * @param defaultThreshold 默认 EOU 概率阈值
 * @param minSilenceMs 触发 EOU 判断前需要等待的最短静音时长
 * @param maxSilenceMs EOU 持续未完成时强制提交的最大静音时长
 * @param inferenceTimeoutMs 单次 EOU 推理的目标超时时间，当前用于配置记录和后续隔离执行器
 * @param language 初版默认语言，真实 ASR 接入后可由 ASR 结果覆盖
 * @author kongweiguang
 */
@ConfigurationProperties(prefix = "kong-voice-agent.eou")
public record EouConfig(
        Boolean enabled,
        String provider,
        String modelPath,
        String tokenizerPath,
        Boolean fallbackEnabled,
        Double defaultThreshold,
        Integer minSilenceMs,
        Integer maxSilenceMs,
        Integer inferenceTimeoutMs,
        String language) {
    /**
     * 归一化默认值，保证开源用户不准备模型时也能启动 mock 闭环。
     */
    public EouConfig {
        if (enabled == null) {
            enabled = true;
        }
        if (fallbackEnabled == null) {
            fallbackEnabled = true;
        }
        provider = provider == null || provider.isBlank() ? "livekit-multilingual" : provider.trim();
        modelPath = modelPath == null || modelPath.isBlank()
                ? "file:models/livekit-turn-detector/model_quantized.onnx"
                : modelPath.trim();
        tokenizerPath = tokenizerPath == null || tokenizerPath.isBlank()
                ? "file:models/livekit-turn-detector/tokenizer.json"
                : tokenizerPath.trim();
        if (defaultThreshold == null || defaultThreshold <= 0 || defaultThreshold >= 1) {
            defaultThreshold = 0.5;
        }
        if (minSilenceMs == null || minSilenceMs <= 0) {
            minSilenceMs = 500;
        }
        if (maxSilenceMs == null || maxSilenceMs <= minSilenceMs) {
            maxSilenceMs = Math.max(1600, minSilenceMs + 500);
        }
        if (inferenceTimeoutMs == null || inferenceTimeoutMs <= 0) {
            inferenceTimeoutMs = 300;
        }
        language = language == null || language.isBlank() ? "zh" : language.trim();
    }
}
