package io.github.kongweiguang.voice.agent.vad;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.github.kongweiguang.voice.agent.audio.PcmUtils;
import io.github.kongweiguang.voice.agent.onnx.OnnxRuntimeConfig;
import io.github.kongweiguang.voice.agent.onnx.OnnxSessionOptionsFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.File;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Silero VAD 适配器，并在模型不可用时提供基于 RMS 的兜底逻辑。
 *
 * @author kongweiguang
 */
@Slf4j
public class SileroVadEngine implements VadEngine {
    /**
     * VAD 模型路径、阈值和兜底策略配置。
     */
    private final VadConfig config;

    /**
     * ONNX Runtime 环境，模型不可用且允许兜底时为空。
     */
    private final OrtEnvironment environment;

    /**
     * Silero ONNX 会话，模型不可用且允许兜底时为空。
     */
    private final OrtSession session;

    /**
     * ONNX 会话选项，模型不可用且允许兜底时为空。
     */
    private final OrtSession.SessionOptions sessionOptions;

    /**
     * Silero 循环状态 h，在连续窗口之间保留。
     */
    private float[][] h = new float[2][64];

    /**
     * Silero 循环状态 c，在连续窗口之间保留。
     */
    private float[][] c = new float[2][64];

    /**
     * 尝试加载 Silero ONNX 模型；失败时根据配置决定是否回退 RMS。
     */
    public SileroVadEngine(VadConfig config, ResourceLoader resourceLoader) {
        this(config, resourceLoader, new OnnxSessionOptionsFactory(new OnnxRuntimeConfig(false, 0, true)));
    }

    /**
     * 尝试加载 Silero ONNX 模型，并按 ONNX Runtime 配置选择 CPU 或 CUDA。
     */
    public SileroVadEngine(VadConfig config,
                           ResourceLoader resourceLoader,
                           OnnxSessionOptionsFactory sessionOptionsFactory) {
        this.config = config;
        OrtEnvironment env = null;
        OrtSession loaded = null;
        OrtSession.SessionOptions options = null;
        try {
            Resource resource = resourceLoader.getResource(config.modelPath());
            if (resource.exists() && resource.isFile()) {
                File model = resource.getFile();
                env = OrtEnvironment.getEnvironment();
                options = sessionOptionsFactory.create();
                loaded = env.createSession(model.getAbsolutePath(), options);
                log.info("Loaded Silero VAD ONNX model from {}", model.getAbsolutePath());
            } else if (!config.fallbackEnabled()) {
                throw new IllegalStateException("VAD model not found and fallback is disabled: " + config.modelPath());
            } else {
                log.warn("Silero VAD model not found at {}, using RMS fallback", config.modelPath());
            }
        } catch (Exception ex) {
            if (!config.fallbackEnabled()) {
                throw new IllegalStateException("Failed to load VAD model and fallback is disabled", ex);
            }
            log.warn("Failed to load Silero VAD ONNX model, using RMS fallback: {}", ex.getMessage());
            closeQuietly(loaded);
            closeQuietly(options);
            if (env != null) {
                env.close();
                env = null;
            }
            loaded = null;
            options = null;
        }
        this.environment = env;
        this.session = loaded;
        this.sessionOptions = options;
    }

    /**
     * 可用时执行 ONNX 推理；否则返回确定性的 RMS 概率，
     * 让语音流水线的其余部分仍可被验证。
     */
    @Override
    public VadDecision detect(String turnId, byte[] pcm) {
        short[] samples = PcmUtils.littleEndianBytesToShorts(pcm.length % 2 == 0 ? pcm : PcmUtils.trimToLatest(pcm, pcm.length - 1));
        double probability = session == null ? rmsFallbackProbability(samples) : inferOrFallback(samples);
        return new VadDecision(turnId, probability, probability >= config.speechThreshold(), Instant.now());
    }

    /**
     * 尝试通用 Silero 输入约定：input、sr、h 和 c。
     */
    private double inferOrFallback(short[] samples) {
        try {
            float[] normalized = new float[samples.length];
            for (int i = 0; i < samples.length; i++) {
                normalized[i] = samples[i] / 32768.0f;
            }
            Map<String, OnnxTensor> tensors = new HashMap<>();
            Map<String, OnnxValue> closable = new HashMap<>();
            try {
                for (String inputName : session.getInputNames()) {
                    OnnxTensor tensor = switch (inputName) {
                        case "input" -> OnnxTensor.createTensor(environment, new float[][]{normalized});
                        case "sr" -> OnnxTensor.createTensor(environment, new long[]{16000L});
                        case "h" -> OnnxTensor.createTensor(environment, new float[][][]{h});
                        case "c" -> OnnxTensor.createTensor(environment, new float[][][]{c});
                        default -> null;
                    };
                    if (tensor != null) {
                        tensors.put(inputName, tensor);
                        closable.put(inputName, tensor);
                    }
                }
                if (!tensors.keySet().containsAll(session.getInputNames())) {
                    return rmsFallbackProbability(samples);
                }
                try (OrtSession.Result result = session.run(tensors)) {
                    Object value = result.get(0).getValue();
                    if (value instanceof float[][] out && out.length > 0 && out[0].length > 0) {
                        captureState(result);
                        return Math.max(0.0, Math.min(1.0, out[0][0]));
                    }
                    if (value instanceof float[] out && out.length > 0) {
                        captureState(result);
                        return Math.max(0.0, Math.min(1.0, out[0]));
                    }
                }
            } finally {
                for (OnnxValue value : closable.values()) {
                    value.close();
                }
            }
        } catch (Exception ex) {
            log.debug("Silero VAD inference failed, using RMS fallback: {}", ex.getMessage());
        }
        return rmsFallbackProbability(samples);
    }

    /**
     * 当模型返回循环状态时，在窗口之间保留 Silero 状态。
     */
    private void captureState(OrtSession.Result result) {
        if (result.size() < 3) {
            return;
        }
        try {
            Object nextH = result.get(1).getValue();
            Object nextC = result.get(2).getValue();
            if (nextH instanceof float[][][] hh && hh.length > 0 && nextC instanceof float[][][] cc && cc.length > 0) {
                h = hh[0];
                c = cc[0];
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * ONNX 模型不可用时使用的轻量说话检测替代逻辑。
     */
    private double rmsFallbackProbability(short[] samples) {
        return Math.min(1.0, PcmUtils.rms(samples) * 20.0);
    }

    @Override
    public boolean modelBacked() {
        return session != null;
    }

    /**
     * 释放 ONNX 会话和运行时环境。
     */
    @Override
    public void close() {
        closeQuietly(session);
        closeQuietly(sessionOptions);
        if (environment != null) {
            environment.close();
        }
    }

    /**
     * 关闭可释放资源，清理时不抛出额外异常。
     */
    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
