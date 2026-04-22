package io.github.kongweiguang.voice.agent.onnx;

import ai.onnxruntime.OrtSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 验证 ONNX Runtime 会话选项工厂的 provider 回退边界。
 *
 * @author kongweiguang
 */
@Tag("config")
@DisplayName("ONNX Runtime 会话选项工厂")
class OnnxSessionOptionsFactoryTest {
    @Test
    @DisplayName("CUDA 不可用且允许回退时仍能创建 CPU 会话选项")
    void fallsBackToCpuWhenCudaUnavailable() {
        OnnxSessionOptionsFactory factory = new OnnxSessionOptionsFactory(new OnnxRuntimeConfig(true, 0, true));

        assertThatCode(() -> {
            try (OrtSession.SessionOptions ignored = factory.create()) {
                // 只验证 provider 选择边界，实际模型加载由 VAD / EOU 测试覆盖。
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("并发启用 CUDA 时每次调用都能创建独立会话选项")
    void createsIndependentOptionsWhenCudaEnabledConcurrently() {
        OnnxSessionOptionsFactory factory = new OnnxSessionOptionsFactory(new OnnxRuntimeConfig(true, 0, true));

        assertThatCode(() -> {
            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
                List<Future<Void>> futures = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    futures.add(executor.submit(() -> {
                        try (OrtSession.SessionOptions options = factory.create()) {
                            // 每次调用都必须得到独立 options，避免并发模型加载共享可关闭状态。
                            assertThat(options).isNotNull();
                        }
                        return null;
                    }));
                }
                for (Future<Void> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }
        }).doesNotThrowAnyException();
    }
}
