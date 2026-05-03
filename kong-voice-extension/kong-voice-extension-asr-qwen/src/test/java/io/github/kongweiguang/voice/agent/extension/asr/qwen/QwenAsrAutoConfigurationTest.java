package io.github.kongweiguang.voice.agent.extension.asr.qwen;

import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapterFactory;
import io.github.kongweiguang.voice.agent.extension.asr.autoconfigure.QwenAsrAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Qwen ASR 扩展的显式启用和业务覆盖能力。
 *
 * @author kongweiguang
 */
@Tag("audio")
@Tag("configuration")
@DisplayName("Qwen ASR 自动装配")
class QwenAsrAutoConfigurationTest {
    /**
     * 自动配置测试上下文。
     */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(QwenAsrAutoConfiguration.class));

    @Test
    @DisplayName("未显式启用时不注册 ASR 工厂")
    void doesNotRegisterFactoryWhenDisabled() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(StreamingAsrAdapterFactory.class));
    }

    @Test
    @DisplayName("显式启用后注册 Qwen ASR 工厂")
    void registersFactoryWhenEnabled() {
        contextRunner.withPropertyValues("kong-voice-agent.asr.qwen.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(StreamingAsrAdapterFactory.class));
    }

    @Test
    @DisplayName("用户自定义 ASR 工厂时默认实现让位")
    void backsOffWhenCustomFactoryExists() {
        StreamingAsrAdapterFactory custom = (sessionId, format) -> null;

        contextRunner.withBean(StreamingAsrAdapterFactory.class, () -> custom)
                .withPropertyValues("kong-voice-agent.asr.qwen.enabled=true")
                .run(context -> assertThat(context.getBean(StreamingAsrAdapterFactory.class)).isSameAs(custom));
    }
}
