package io.github.kongweiguang.voice.agent.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证音频格式配置绑定和默认值边界。
 *
 * @author kongweiguang
 */
@Tag("config")
@Tag("audio")
@DisplayName("音频格式配置")
class AudioFormatSpecTest {
    /**
     * 用最小 Spring 上下文验证配置属性绑定，不启动完整应用。
     */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AudioFormatSpecTestConfiguration.class);

    /**
     * kong-voice-agent.audio 是服务端音频格式的公开配置入口。
     */
    @Test
    @DisplayName("从 kong-voice-agent 前缀读取音频格式配置")
    void bindsAudioFormatFromKongVoiceAgentPrefix() {
        contextRunner
                .withPropertyValues(
                        "kong-voice-agent.audio.sample-rate=8000",
                        "kong-voice-agent.audio.channels=2",
                        "kong-voice-agent.audio.sample-format=s16le",
                        "kong-voice-agent.audio.upload-chunk-ms=40"
                )
                .run(context -> {
                    AudioFormatSpec spec = context.getBean(AudioFormatSpec.class);

                    assertThat(spec.sampleRate()).isEqualTo(8000);
                    assertThat(spec.channels()).isEqualTo(2);
                    assertThat(spec.sampleFormat()).isEqualTo("s16le");
                    assertThat(spec.uploadChunkMs()).isEqualTo(40);
                    assertThat(spec.bytesForMs(40)).isEqualTo(1280);
                });
    }

    /**
     * 配置缺失或传入非正数时回落到协议默认值，避免会话缓冲区被错误配置破坏。
     */
    @Test
    @DisplayName("音频格式缺省值使用协议默认值")
    void usesDefaultAudioFormatWhenPropertiesAreMissingOrInvalid() {
        contextRunner
                .withPropertyValues(
                        "kong-voice-agent.audio.sample-rate=0",
                        "kong-voice-agent.audio.channels=-1",
                        "kong-voice-agent.audio.sample-format=",
                        "kong-voice-agent.audio.upload-chunk-ms=0"
                )
                .run(context -> {
                    AudioFormatSpec spec = context.getBean(AudioFormatSpec.class);

                    assertThat(spec).isEqualTo(AudioFormatSpec.DEFAULT);
                    assertThat(spec.bytesForMs(20)).isEqualTo(640);
                });
    }

    /**
     * 测试专用配置，只启用音频格式配置属性 Bean。
     *
     * @author kongweiguang
     */
    @Configuration
    @EnableConfigurationProperties(AudioFormatSpec.class)
    static class AudioFormatSpecTestConfiguration {
    }
}
