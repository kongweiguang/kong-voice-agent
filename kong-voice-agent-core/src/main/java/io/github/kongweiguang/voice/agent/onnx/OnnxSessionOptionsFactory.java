package io.github.kongweiguang.voice.agent.onnx;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ONNX Runtime 会话选项工厂，集中封装 CPU / CUDA provider 选择策略。
 *
 * @author kongweiguang
 */
@Slf4j
@RequiredArgsConstructor
public class OnnxSessionOptionsFactory {
    /**
     * ONNX Runtime 执行设备配置。
     */
    private final OnnxRuntimeConfig config;

    /**
     * 为单个 ONNX session 创建独立选项，调用方负责随 session 生命周期关闭返回值。
     */
    public OrtSession.SessionOptions create() throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        if (!Boolean.TRUE.equals(config.gpuEnabled())) {
            return options;
        }
        try {
            options.addCUDA(config.gpuDeviceId());
            log.info("ONNX Runtime CUDA provider enabled on device {}", config.gpuDeviceId());
            return options;
        } catch (OrtException ex) {
            closeQuietly(options);
            if (!Boolean.TRUE.equals(config.fallbackToCpu())) {
                throw ex;
            }
            log.warn("ONNX Runtime CUDA provider unavailable, falling back to CPU: {}", ex.getMessage());
            return new OrtSession.SessionOptions();
        }
    }

    /**
     * 关闭创建失败路径上的会话选项，避免掩盖 CUDA provider 原始异常。
     */
    private void closeQuietly(OrtSession.SessionOptions options) {
        try {
            options.close();
        } catch (Exception ignored) {
        }
    }
}
