package io.github.kongweiguang.voice.agent.onnx;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ONNX Runtime 执行提供方配置，统一控制 VAD 和 EOU 等本地 ONNX 模型的运行设备。
 *
 * @param gpuEnabled 是否优先使用 CUDA GPU 执行提供方
 * @param gpuDeviceId CUDA 设备编号，单卡部署通常使用 0
 * @param fallbackToCpu GPU 提供方不可用时是否自动回退 CPU 执行
 * @author kongweiguang
 */
@ConfigurationProperties(prefix = "kong-voice-agent.onnx")
public record OnnxRuntimeConfig(
        Boolean gpuEnabled,
        int gpuDeviceId,
        Boolean fallbackToCpu) {
    /**
     * 归一化默认值，保证缺省运行仍使用无需额外 native 依赖的 CPU 模式。
     */
    public OnnxRuntimeConfig {
        if (gpuEnabled == null) {
            gpuEnabled = false;
        }
        if (gpuDeviceId < 0) {
            gpuDeviceId = 0;
        }
        if (fallbackToCpu == null) {
            fallbackToCpu = true;
        }
    }
}
