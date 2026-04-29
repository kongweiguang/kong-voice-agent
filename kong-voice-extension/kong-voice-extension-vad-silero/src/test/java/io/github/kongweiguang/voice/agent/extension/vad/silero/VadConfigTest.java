package io.github.kongweiguang.voice.agent.extension.vad.silero;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 VAD 配置前缀和缺省值边界。
 *
 * @author kongweiguang
 */
@Tag("config")
@DisplayName("VAD 配置绑定")
class VadConfigTest {
    /**
     * 用最小 Spring 上下文验证配置属性绑定，不启动完整应用。
     */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(VadConfigTestConfiguration.class);

    /**
     * 新的 kong-voice-agent 前缀需要成为唯一公开配置入口。
     */
    @Test
    @DisplayName("使用 kong-voice-agent 前缀读取 VAD 配置")
    void bindsVadConfigFromKongVoiceAgentPrefix() {
        contextRunner
                .withPropertyValues(
                        "kong-voice-agent.vad.model-path=file:test-model.onnx",
                        "kong-voice-agent.vad.speech-threshold=0.72",
                        "kong-voice-agent.vad.fallback-enabled=true"
                )
                .run(context -> {
                    VadConfig config = context.getBean(VadConfig.class);

                    assertThat(config.modelPath()).isEqualTo("file:test-model.onnx");
                    assertThat(config.speechThreshold()).isEqualTo(0.72);
                    assertThat(config.fallbackEnabled()).isTrue();
                });
    }

    /**
     * 旧 voice-agent 前缀不再参与绑定，避免外部配置入口产生歧义。
     */
    @Test
    @DisplayName("不再从 voice-agent 前缀读取 VAD 配置")
    void doesNotBindVadConfigFromOldVoiceAgentPrefix() {
        contextRunner
                .withPropertyValues(
                        "voice-agent.vad.model-path=file:old-model.onnx",
                        "voice-agent.vad.speech-threshold=0.91",
                        "voice-agent.vad.fallback-enabled=true"
                )
                .run(context -> {
                    VadConfig config = context.getBean(VadConfig.class);

                    assertThat(config.modelPath()).isEqualTo("file:models/silero_vad.onnx");
                    assertThat(config.speechThreshold()).isEqualTo(0.6);
                    assertThat(config.fallbackEnabled()).isFalse();
                });
    }

    /**
     * 测试专用配置，只启用 VAD 配置属性 Bean。
     *
     * @author kongweiguang
     */
    @Configuration
    @EnableConfigurationProperties(VadConfig.class)
    static class VadConfigTestConfiguration {
    }
}
