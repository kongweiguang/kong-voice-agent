package io.github.kongweiguang.voice.agent.eou;

import io.github.kongweiguang.voice.agent.autoconfigure.VoiceDefaultAutoConfiguration;
import io.github.kongweiguang.voice.agent.extension.eou.autoconfigure.LiveKitEouAutoConfiguration;
import io.github.kongweiguang.voice.agent.extension.vad.autoconfigure.SileroVadAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 LiveKit EOU 扩展的默认装配和业务覆盖能力。
 *
 * @author kongweiguang
 */
@Tag("eou")
@DisplayName("EOU 自动装配")
class EouAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    VoiceDefaultAutoConfiguration.class,
                    SileroVadAutoConfiguration.class,
                    LiveKitEouAutoConfiguration.class
            ))
            .withPropertyValues(
                    "kong-voice-agent.eou.model-path=file:missing-eou.onnx",
                    "kong-voice-agent.eou.tokenizer-path=file:missing-tokenizer.json",
                    "kong-voice-agent.vad.model-path=file:missing-vad.onnx",
                    "kong-voice-agent.vad.fallback-enabled=true"
            );

    @Test
    @DisplayName("默认注册可运行的 EOU detector")
    void registersDefaultEouDetector() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(EouDetector.class));
    }

    @Test
    @DisplayName("用户自定义 EOU detector 时默认实现让位")
    void backsOffWhenCustomDetectorExists() {
        EouDetector custom = ignored -> new EouPrediction(true, 1.0, 0.5, "custom_rule", false);

        contextRunner.withBean(EouDetector.class, () -> custom)
                .run(context -> assertThat(context.getBean(EouDetector.class)).isSameAs(custom));
    }

    @Test
    @DisplayName("用户可只提供历史读取能力")
    void allowsCustomHistoryProvider() {
        EouHistoryProvider custom = (sessionId, maxTurns) -> java.util.List.of();

        contextRunner.withBean(EouHistoryProvider.class, () -> custom)
                .run(context -> assertThat(context.getBean(EouHistoryProvider.class)).isSameAs(custom));
    }
}
