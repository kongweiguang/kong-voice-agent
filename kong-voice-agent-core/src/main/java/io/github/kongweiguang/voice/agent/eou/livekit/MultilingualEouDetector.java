package io.github.kongweiguang.voice.agent.eou.livekit;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.eou.EouContext;
import io.github.kongweiguang.voice.agent.eou.EouDetector;
import io.github.kongweiguang.voice.agent.eou.EouHistoryProvider;
import io.github.kongweiguang.voice.agent.eou.EouPrediction;
import io.github.kongweiguang.voice.agent.eou.NoopEouDetector;
import io.github.kongweiguang.voice.agent.onnx.OnnxRuntimeConfig;
import io.github.kongweiguang.voice.agent.onnx.OnnxSessionOptionsFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 LiveKit MultilingualModel 思路的本地 ONNX EOU detector。
 *
 * @author kongweiguang
 */
@Slf4j
public class MultilingualEouDetector implements EouDetector {
    /**
     * EOU 模型加载和兜底配置。
     */
    private final EouConfig config;

    /**
     * LiveKit prompt 构造器。
     */
    private final LiveKitEouPromptBuilder promptBuilder;

    /**
     * 模型不可用或推理失败时使用的兜底实现。
     */
    private final EouDetector fallback = new NoopEouDetector();

    /**
     * ONNX Runtime 环境，模型不可用时为空。
     */
    private final OrtEnvironment environment;

    /**
     * EOU ONNX 会话，模型不可用时为空。
     */
    private final OrtSession session;

    /**
     * ONNX 会话选项，模型不可用时为空。
     */
    private final OrtSession.SessionOptions sessionOptions;

    /**
     * Hugging Face tokenizer，模型不可用时为空。
     */
    private final HuggingFaceTokenizer tokenizer;

    /**
     * ONNX Runtime CUDA provider 与 tokenizer 都包含 native 状态，默认 detector 是单例 Bean，
     * 多会话并发判断时需要串行化真实推理段，避免 native 组件并发访问导致 JVM 崩溃。
     */
    private final ReentrantLock inferenceLock = new ReentrantLock();

    /**
     * Qwen chat template 的结束 token id，LiveKit EOU 通过该 token 概率判断是否结束。
     */
    private final long eouTokenId;

    /**
     * 尝试加载 LiveKit turn detector 相关模型资产。
     */
    public MultilingualEouDetector(EouConfig config,
                                   EouHistoryProvider historyProvider,
                                   ResourceLoader resourceLoader) {
        this(config, historyProvider, resourceLoader, new OnnxSessionOptionsFactory(new OnnxRuntimeConfig(false, 0, true)));
    }

    /**
     * 尝试加载 LiveKit turn detector，并按 ONNX Runtime 配置选择 CPU 或 CUDA。
     */
    public MultilingualEouDetector(EouConfig config,
                                   EouHistoryProvider historyProvider,
                                   ResourceLoader resourceLoader,
                                   OnnxSessionOptionsFactory sessionOptionsFactory) {
        this.config = config;
        this.promptBuilder = new LiveKitEouPromptBuilder(historyProvider);
        OrtEnvironment env = null;
        OrtSession loadedSession = null;
        OrtSession.SessionOptions options = null;
        HuggingFaceTokenizer loadedTokenizer = null;
        long loadedEouTokenId = -1;
        try {
            if (!"livekit-multilingual".equals(config.provider())) {
                throw new IllegalStateException("Unsupported EOU provider: " + config.provider());
            }
            // EOU 默认依赖本地 ONNX 模型和 tokenizer，二者任一缺失都进入统一 fallback 策略。
            File model = resolveFile(resourceLoader, config.modelPath(), "EOU model");
            File tokenizerFile = resolveFile(resourceLoader, config.tokenizerPath(), "EOU tokenizer");
            env = OrtEnvironment.getEnvironment();
            options = sessionOptionsFactory.create();
            loadedSession = env.createSession(model.getAbsolutePath(), options);
            loadedTokenizer = HuggingFaceTokenizer.newInstance(Path.of(tokenizerFile.toURI()));
            long[] eouTokenIds = loadedTokenizer.encode("<|im_end|>", false, false).getIds();
            if (eouTokenIds.length != 1) {
                throw new IllegalStateException("EOU tokenizer must encode <|im_end|> as one token");
            }
            loadedEouTokenId = eouTokenIds[0];
            log.info("Loaded EOU ONNX model from {}, tokenizer from {}", model.getAbsolutePath(), tokenizerFile.getAbsolutePath());
        } catch (Exception ex) {
            if (!config.fallbackEnabled()) {
                throw new IllegalStateException("Failed to load EOU model and fallback is disabled", ex);
            }
            log.warn("EOU model unavailable, using silence fallback: {}", ex.getMessage());
            closeQuietly(loadedSession);
            closeQuietly(options);
            closeQuietly(loadedTokenizer);
            if (env != null) {
                env.close();
                env = null;
            }
            loadedSession = null;
            options = null;
            loadedTokenizer = null;
        }
        this.environment = env;
        this.session = loadedSession;
        this.sessionOptions = options;
        this.tokenizer = loadedTokenizer;
        this.eouTokenId = loadedEouTokenId;
    }

    @Override
    public EouPrediction predict(EouContext context) {
        if (session == null || tokenizer == null) {
            return fallback.predict(context);
        }
        double threshold = config.defaultThreshold();
        try {
            String prompt = promptBuilder.build(context);
            inferenceLock.lock();
            try {
                // tokenizer 与 ONNX session 都可能包含 native 状态，真实推理段通过锁串行化。
                Encoding encoding = tokenizer.encode(prompt, true, false);
                long[] ids = encoding.getIds();
                if (ids.length == 0) {
                    return EouPrediction.waiting(0.0, threshold, true);
                }
                double probability = inferProbability(ids);
                return probability >= threshold
                        ? EouPrediction.detected(probability, threshold, true)
                        : EouPrediction.waiting(probability, threshold, true);
            } finally {
                inferenceLock.unlock();
            }
        } catch (Exception ex) {
            if (!config.fallbackEnabled()) {
                throw new IllegalStateException("EOU inference failed and fallback is disabled", ex);
            }
            log.debug("EOU inference failed, using silence fallback: {}", ex.getMessage());
            return fallback.predict(context);
        }
    }

    /**
     * 解析必须存在且可作为本地文件读取的模型资产。
     */
    private File resolveFile(ResourceLoader resourceLoader, String location, String name) throws Exception {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists() || !resource.isFile()) {
            throw new IllegalStateException(name + " not found: " + location);
        }
        return resource.getFile();
    }

    /**
     * 使用常见 Hugging Face ONNX 输入约定运行模型并读取最后一个结束概率。
     */
    private double inferProbability(long[] inputIds) throws Exception {
        Map<String, OnnxTensor> tensors = new HashMap<>();
        Map<String, OnnxValue> closable = new HashMap<>();
        try {
            long[][] ids = new long[][]{inputIds};
            long[][] attentionMask = new long[][]{ones(inputIds.length)};
            long[][] tokenTypeIds = new long[][]{new long[inputIds.length]};
            for (String inputName : session.getInputNames()) {
                // 按 Hugging Face 常见输入名构造 tensor，不认识的输入直接视为模型签名不兼容。
                OnnxTensor tensor = switch (inputName) {
                    case "input_ids" -> OnnxTensor.createTensor(environment, ids);
                    case "attention_mask" -> OnnxTensor.createTensor(environment, attentionMask);
                    case "token_type_ids" -> OnnxTensor.createTensor(environment, tokenTypeIds);
                    default -> null;
                };
                if (tensor != null) {
                    tensors.put(inputName, tensor);
                    closable.put(inputName, tensor);
                }
            }
            if (!tensors.keySet().containsAll(session.getInputNames())) {
                throw new IllegalStateException("Unsupported EOU ONNX inputs: " + session.getInputNames());
            }
            try (OrtSession.Result result = session.run(tensors)) {
                return extractEouProbability(result.get(0).getValue(), eouTokenId);
            }
        } finally {
            for (OnnxValue value : closable.values()) {
                value.close();
            }
        }
    }

    /**
     * 创建 attention mask。
     */
    private long[] ones(int length) {
        long[] values = new long[length];
        for (int i = 0; i < length; i++) {
            values[i] = 1L;
        }
        return values;
    }

    /**
     * 从 ONNX 输出中取最后一个位置的 im_end token 概率；若输出已是单概率，则使用 sigmoid 兜底。
     */
    private double extractEouProbability(Object value, long tokenId) {
        return switch (value) {
            case float[] out when out.length > tokenId -> softmaxProbability(out, (int) tokenId);
            case float[] out when out.length > 0 -> sigmoid(out[out.length - 1]);
            case float[][] out when out.length > 0 && out[out.length - 1].length > tokenId -> softmaxProbability(out[out.length - 1], (int) tokenId);
            case float[][] out when out.length > 0 && out[out.length - 1].length > 0 -> sigmoid(out[out.length - 1][out[out.length - 1].length - 1]);
            case float[][][] out when out.length > 0 && out[0].length > 0 && out[0][out[0].length - 1].length > tokenId -> softmaxProbability(out[0][out[0].length - 1], (int) tokenId);
            case double[] out when out.length > tokenId -> softmaxProbability(out, (int) tokenId);
            case double[] out when out.length > 0 -> sigmoid(out[out.length - 1]);
            case double[][] out when out.length > 0 && out[out.length - 1].length > tokenId -> softmaxProbability(out[out.length - 1], (int) tokenId);
            case double[][] out when out.length > 0 && out[out.length - 1].length > 0 -> sigmoid(out[out.length - 1][out[out.length - 1].length - 1]);
            default -> throw new IllegalStateException("Unsupported EOU ONNX output: " + value.getClass().getName());
        };
    }

    /**
     * 对 logits 做稳定 softmax，并取目标 token 的概率。
     */
    private double softmaxProbability(float[] logits, int tokenId) {
        double max = Double.NEGATIVE_INFINITY;
        for (float logit : logits) {
            max = Math.max(max, logit);
        }
        double sum = 0.0;
        for (float logit : logits) {
            sum += Math.exp(logit - max);
        }
        return Math.exp(logits[tokenId] - max) / sum;
    }

    /**
     * 对 double logits 做稳定 softmax，并取目标 token 的概率。
     */
    private double softmaxProbability(double[] logits, int tokenId) {
        double max = Double.NEGATIVE_INFINITY;
        for (double logit : logits) {
            max = Math.max(max, logit);
        }
        double sum = 0.0;
        for (double logit : logits) {
            sum += Math.exp(logit - max);
        }
        return Math.exp(logits[tokenId] - max) / sum;
    }

    /**
     * 单值 logits 的概率兜底。
     */
    private double sigmoid(double raw) {
        double probability = raw < 0.0 || raw > 1.0 ? 1.0 / (1.0 + Math.exp(-raw)) : raw;
        return Math.max(0.0, Math.min(1.0, probability));
    }

    /**
     * 关闭可释放资源，构造失败路径不能掩盖原始异常。
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

    @Override
    public void close() {
        closeQuietly(session);
        closeQuietly(sessionOptions);
        closeQuietly(tokenizer);
        if (environment != null) {
            environment.close();
        }
    }
}
