package io.github.kongweiguang.voice.agent.onnx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 ONNX Runtime 执行设备配置绑定和默认值。
 *
 * @author kongweiguang
 */
@Tag("config")
@DisplayName("ONNX Runtime 配置绑定")
class OnnxRuntimeConfigTest {
    /**
     * 用最小 Spring 上下文验证配置属性绑定，不启动完整应用。
     */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OnnxRuntimeConfigTestConfiguration.class);

    @Test
    @DisplayName("缺省使用 CPU 且允许 GPU 不可用时回退")
    void defaultsToCpu() {
        contextRunner.run(context -> {
            OnnxRuntimeConfig config = context.getBean(OnnxRuntimeConfig.class);

            assertThat(config.gpuEnabled()).isFalse();
            assertThat(config.gpuDeviceId()).isZero();
            assertThat(config.fallbackToCpu()).isTrue();
        });
    }

    @Test
    @DisplayName("使用 kong-voice-agent 前缀读取 ONNX GPU 开关")
    void bindsOnnxRuntimeConfigFromKongVoiceAgentPrefix() {
        contextRunner
                .withPropertyValues(
                        "kong-voice-agent.onnx.gpu-enabled=true",
                        "kong-voice-agent.onnx.gpu-device-id=1",
                        "kong-voice-agent.onnx.fallback-to-cpu=false"
                )
                .run(context -> {
                    OnnxRuntimeConfig config = context.getBean(OnnxRuntimeConfig.class);

                    assertThat(config.gpuEnabled()).isTrue();
                    assertThat(config.gpuDeviceId()).isEqualTo(1);
                    assertThat(config.fallbackToCpu()).isFalse();
                });
    }

    /**
     * 测试专用配置，只启用 ONNX Runtime 配置属性 Bean。
     *
     * @author kongweiguang
     */
    @Configuration
    @EnableConfigurationProperties(OnnxRuntimeConfig.class)
    static class OnnxRuntimeConfigTestConfiguration {
    }
}
