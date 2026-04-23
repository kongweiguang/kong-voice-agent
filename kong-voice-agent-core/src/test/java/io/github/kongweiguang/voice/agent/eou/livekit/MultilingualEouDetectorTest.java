package io.github.kongweiguang.voice.agent.eou.livekit;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.eou.EouContext;
import io.github.kongweiguang.voice.agent.eou.EouPrediction;
import io.github.kongweiguang.voice.agent.onnx.OnnxRuntimeConfig;
import io.github.kongweiguang.voice.agent.onnx.OnnxSessionOptionsFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 LiveKit 多语言 EOU detector 的 predict 行为。
 *
 * @author kongweiguang
 */
@Tag("eou")
@DisplayName("LiveKit 多语言 EOU detector")
class MultilingualEouDetectorTest {
    @Test
    @Tag("model")
    @EnabledIfSystemProperty(named = "kong.voice-agent.model-tests", matches = "true")
    @DisplayName("predict 使用默认 LiveKit 模型文件完成真实推理")
    void predictWithDefaultModelAssets() {
        Path projectRoot = projectRoot();
        Path model = projectRoot.resolve("models/livekit-turn-detector/model_quantized.onnx");
        Path tokenizer = projectRoot.resolve("models/livekit-turn-detector/tokenizer.json");
        assumeTrue(Files.isRegularFile(model), "缺少真实 EOU 模型文件，跳过模型集成测试");
        assumeTrue(Files.isRegularFile(tokenizer), "缺少真实 EOU tokenizer 文件，跳过模型集成测试");
        EouConfig config = new EouConfig(
                true,
                "livekit-multilingual",
                "file:models/livekit-turn-detector/model_quantized.onnx",
                "file:models/livekit-turn-detector/tokenizer.json",
                false,
                0.5,
                500,
                1600,
                300,
                "zh"
        );
        try (MultilingualEouDetector detector = new MultilingualEouDetector(
                config,
                (sessionId, maxTurns) -> java.util.List.of(),
                modelResourceLoader(projectRoot)
        )) {
            EouPrediction prediction = detector.predict(new EouContext("session-1", "turn-9", "我想查询明天上海的天气", "zh", 700L));

            assertThat(prediction.probability()).isBetween(0.0, 1.0);
            assertThat(prediction.threshold()).isEqualTo(0.5);
            assertThat(prediction.reason()).isIn("eou_detected", "eou_waiting");
            assertThat(prediction.modelBacked()).isTrue();
        }
    }

    @Test
    @Tag("model")
    @EnabledIfSystemProperty(named = "kong.voice-agent.model-tests", matches = "true")
    @DisplayName("CUDA 开启时并发执行 EOU 判断")
    void predictsConcurrentlyWithCudaEnabled() throws Exception {
        predictsConcurrentlyWithCudaConfig(true);
    }

    @Test
    @Tag("model")
    @Tag("cuda")
    @EnabledIfSystemProperty(named = "kong.voice-agent.model-tests", matches = "true")
    @EnabledIfSystemProperty(named = "kong.voice-agent.cuda-tests", matches = "true")
    @DisplayName("CUDA 严格模式下执行单次 EOU 判断")
    void predictsOnceWithCudaRequired() throws Exception {
        try (MultilingualEouDetector detector = cudaDetector(false)) {
            EouPrediction prediction = detector.predict(new EouContext(
                    "session-cuda",
                    "turn-1",
                    "我想查询明天上海的天气",
                    "zh",
                    700L
            ));

            assertThat(prediction.probability()).isBetween(0.0, 1.0);
            assertThat(prediction.threshold()).isEqualTo(0.5);
            assertThat(prediction.reason()).isIn("eou_detected", "eou_waiting");
        }
    }

    @Test
    @Tag("model")
    @Tag("cuda")
    @EnabledIfSystemProperty(named = "kong.voice-agent.model-tests", matches = "true")
    @EnabledIfSystemProperty(named = "kong.voice-agent.cuda-tests", matches = "true")
    @DisplayName("CUDA 严格模式下并发执行 EOU 判断")
    void predictsConcurrentlyWithCudaRequired() throws Exception {
        predictsConcurrentlyWithCudaConfig(false);
    }

    /**
     * 使用真实 EOU 模型并发执行判断；fallbackToCpu=false 时可严格验证 CUDA provider 是否真的可用。
     */
    private void predictsConcurrentlyWithCudaConfig(boolean fallbackToCpu) throws Exception {
        try (MultilingualEouDetector detector = cudaDetector(fallbackToCpu)) {
            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
                List<Future<EouPrediction>> futures = new ArrayList<>();
                for (int i = 0; i < 800; i++) {
                    int index = i;
                    futures.add(executor.submit(() -> detector.predict(new EouContext(
                            "session-" + index,
                            "turn-" + (index + 1),
                            "我想查询明天上海的天气，然后再看看后天是否下雨",
                            "zh",
                            700L
                    ))));
                }
                for (Future<EouPrediction> future : futures) {
                    EouPrediction prediction = future.get(30, TimeUnit.SECONDS);
                    assertThat(prediction.probability()).isBetween(0.0, 1.0);
                    assertThat(prediction.threshold()).isEqualTo(0.5);
                    assertThat(prediction.reason()).isIn("eou_detected", "eou_waiting", "eou_unavailable_fallback");
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * 创建启用 CUDA 的真实 EOU detector；fallbackToCpu=false 时可验证是否真的使用 CUDA provider。
     */
    private MultilingualEouDetector cudaDetector(boolean fallbackToCpu) throws Exception {
        Path projectRoot = projectRoot();
        Path model = projectRoot.resolve("models/livekit-turn-detector/model_quantized.onnx");
        Path tokenizer = projectRoot.resolve("models/livekit-turn-detector/tokenizer.json");
        assumeTrue(Files.isRegularFile(model), "缺少真实 EOU 模型文件，跳过模型集成测试");
        assumeTrue(Files.isRegularFile(tokenizer), "缺少真实 EOU tokenizer 文件，跳过模型集成测试");
        EouConfig config = new EouConfig(
                true,
                "livekit-multilingual",
                "file:models/livekit-turn-detector/model_quantized.onnx",
                "file:models/livekit-turn-detector/tokenizer.json",
                true,
                0.5,
                500,
                1600,
                300,
                "zh"
        );
        OnnxSessionOptionsFactory sessionOptionsFactory =
                new OnnxSessionOptionsFactory(new OnnxRuntimeConfig(true, 0, fallbackToCpu));
        return new MultilingualEouDetector(
                config,
                (sessionId, maxTurns) -> java.util.List.of(),
                modelResourceLoader(projectRoot),
                sessionOptionsFactory
        );
    }

    @Test
    @DisplayName("predict 在模型推理概率达到阈值时返回结束")
    void predictReturnsDetectedWhenModelProbabilityReachesThreshold() throws Exception {
        MultilingualEouDetector detector = detectorWithMissingAssets();
        OrtSession session = mock(OrtSession.class);
        HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);
        Encoding encoding = mock(Encoding.class);
        OrtSession.Result result = mock(OrtSession.Result.class);
        OnnxValue output = mock(OnnxValue.class);
        when(tokenizer.encode(anyString(), eq(true), eq(false))).thenReturn(encoding);
        when(encoding.getIds()).thenReturn(new long[]{101, 202, 303});
        when(session.getInputNames()).thenReturn(Set.of("input_ids", "attention_mask"));
        when(output.getValue()).thenReturn(new float[]{0.0F, 8.0F});
        when(result.get(0)).thenReturn(output);
        when(session.run(anyMap())).thenReturn(result);
        setField(detector, "environment", OrtEnvironment.getEnvironment());
        setField(detector, "session", session);
        setField(detector, "tokenizer", tokenizer);
        setField(detector, "eouTokenId", 1L);

        EouPrediction prediction = detector.predict(new EouContext("session-1", "turn-9", "我说完了", "zh", 700L));

        assertThat(prediction.finished()).isTrue();
        assertThat(prediction.probability()).isGreaterThanOrEqualTo(0.7);
        assertThat(prediction.threshold()).isEqualTo(0.7);
        assertThat(prediction.reason()).isEqualTo("eou_detected");
        assertThat(prediction.modelBacked()).isTrue();
    }

    /**
     * 模型文件缺失时，开源默认配置必须仍能通过静音兜底完成端点判断。
     */
    @Test
    @DisplayName("predict 在模型不可用时返回静音兜底结果")
    void predictFallsBackWhenModelAssetsUnavailable() {
        MultilingualEouDetector detector = detectorWithMissingAssets();

        EouPrediction prediction = detector.predict(new EouContext("session-1", "turn-9", "我说完了", "zh", 700L));

        assertThat(prediction.finished()).isTrue();
        assertThat(prediction.probability()).isEqualTo(1.0);
        assertThat(prediction.threshold()).isEqualTo(1.0);
        assertThat(prediction.reason()).isEqualTo("eou_unavailable_fallback");
        assertThat(prediction.modelBacked()).isFalse();
    }

    /**
     * 构造模型资产缺失的 detector，便于测试按需替换内部模型组件。
     */
    private MultilingualEouDetector detectorWithMissingAssets() {
        EouConfig config = new EouConfig(
                true,
                "livekit-multilingual",
                "file:target/missing-eou-model.onnx",
                "file:target/missing-eou-tokenizer.json",
                true,
                0.7,
                500,
                1600,
                300,
                "zh"
        );
        return new MultilingualEouDetector(
                config,
                (sessionId, maxTurns) -> java.util.List.of(),
                new DefaultResourceLoader()
        );
    }

    /**
     * 测试中替换模型组件，避免单元测试依赖真实大模型文件。
     */
    private void setField(MultilingualEouDetector detector, String name, Object value) throws Exception {
        Field field = MultilingualEouDetector.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(detector, value);
    }

    /**
     * Maven 子模块测试的工作目录可能是 core 模块，需要回退到仓库根目录定位外置模型。
     */
    private Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        return current.getFileName().toString().equals("kong-voice-agent-core") ? current.getParent() : current;
    }

    /**
     * 子模块测试进程的工作目录不固定，测试 loader 只负责把默认模型相对路径映射到仓库根目录。
     */
    private ResourceLoader modelResourceLoader(Path projectRoot) {
        DefaultResourceLoader fallback = new DefaultResourceLoader();
        return new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                if (location.startsWith("file:models/")) {
                    return new FileSystemResource(projectRoot.resolve(location.substring("file:".length())));
                }
                return fallback.getResource(location);
            }

            @Override
            public ClassLoader getClassLoader() {
                return fallback.getClassLoader();
            }
        };
    }
}
