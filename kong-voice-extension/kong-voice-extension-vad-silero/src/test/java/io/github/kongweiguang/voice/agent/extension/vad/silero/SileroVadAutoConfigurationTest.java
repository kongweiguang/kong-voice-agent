package io.github.kongweiguang.voice.agent.extension.vad.silero;

import io.github.kongweiguang.voice.agent.extension.vad.autoconfigure.SileroVadAutoConfiguration;
import io.github.kongweiguang.voice.agent.extension.vad.silero.OnnxSessionOptionsFactory;
import io.github.kongweiguang.voice.agent.vad.VadDecision;
import io.github.kongweiguang.voice.agent.vad.VadEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Silero VAD 扩展的默认装配和业务覆盖能力。
 *
 * @author kongweiguang
 */
@Tag("vad")
@DisplayName("Silero VAD 自动装配")
class SileroVadAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SileroVadAutoConfiguration.class
            ))
            .withPropertyValues(
                    "kong-voice-agent.vad.model-path=file:missing-silero.onnx",
                    "kong-voice-agent.vad.fallback-enabled=true"
            );

    @Test
    @DisplayName("默认注册 VAD 与 ONNX 会话工厂")
    void registersDefaultVadAndOnnxFactory() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(VadEngine.class);
            assertThat(context).hasSingleBean(OnnxSessionOptionsFactory.class);
        });
    }

    @Test
    @DisplayName("用户自定义 VAD 时默认实现让位")
    void backsOffWhenCustomVadExists() {
        VadEngine custom = new VadEngine() {
            @Override
            public VadDecision detect(String turnId, byte[] pcm) {
                return new VadDecision(turnId, 1.0, true, java.time.Instant.now());
            }

            @Override
            public boolean modelBacked() {
                return false;
            }

            @Override
            public void close() {
            }
        };

        contextRunner.withBean(VadEngine.class, () -> custom)
                .run(context -> assertThat(context.getBean(VadEngine.class)).isSameAs(custom));
    }
}
