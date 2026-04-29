package io.github.kongweiguang.voice.agent.extension.tts.autoconfigure;

import io.github.kongweiguang.voice.agent.extension.tts.openai.OpenAiTtsOrchestrator;
import io.github.kongweiguang.voice.agent.extension.tts.openai.OpenAiTtsProperties;
import io.github.kongweiguang.voice.agent.tts.TtsOrchestrator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * OpenAI TTS 扩展自动配置，默认向外暴露 TTS 编排器。
 *
 * @author kongweiguang
 */
@AutoConfiguration
@EnableConfigurationProperties(OpenAiTtsProperties.class)
public class OpenAiTtsAutoConfiguration {
    /**
     * 默认 TTS 编排器，业务侧声明同类型 Bean 后会自动替换。
     */
    @Bean
    @ConditionalOnMissingBean(TtsOrchestrator.class)
    public TtsOrchestrator ttsOrchestrator(OpenAiTtsProperties ttsProperties) {
        return new OpenAiTtsOrchestrator(ttsProperties);
    }
}
