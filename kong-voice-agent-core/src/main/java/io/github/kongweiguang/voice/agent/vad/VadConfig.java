package io.github.kongweiguang.voice.agent.vad;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Silero VAD 加载和 RMS 兜底行为的外部配置。
 *
 * @param modelPath       Silero ONNX 模型路径，支持 Spring Resource 路径写法
 * @param speechThreshold 判定为说话的概率阈值
 * @param fallbackEnabled 模型缺失或推理失败时是否允许使用 RMS 兜底
 * @author kongweiguang
 */
@ConfigurationProperties(prefix = "kong-voice-agent.vad")
public record VadConfig(String modelPath, Double speechThreshold, Boolean fallbackEnabled) {
    /**
     * 归一化外部配置，保证缺省配置下仍具备可运行的 VAD 策略。
     */
    public VadConfig {
        if (modelPath == null || modelPath.isBlank()) {
            modelPath = "file:models/silero_vad.onnx";
        }
        if (speechThreshold == null || speechThreshold <= 0) {
            speechThreshold = 0.6;
        }
        fallbackEnabled = Boolean.TRUE.equals(fallbackEnabled);
    }
}
