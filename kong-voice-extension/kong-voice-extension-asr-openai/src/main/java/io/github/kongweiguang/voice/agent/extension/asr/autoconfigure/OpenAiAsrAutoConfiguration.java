package io.github.kongweiguang.voice.agent.extension.asr.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapterFactory;
import io.github.kongweiguang.voice.agent.extension.asr.openai.OpenAiAsrProperties;
import io.github.kongweiguang.voice.agent.extension.asr.openai.OpenAiStreamingAsrAdapterFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * OpenAI ASR 扩展自动配置，默认向外暴露 ASR 工厂。
 *
 * @author kongweiguang
 */
@AutoConfiguration
@EnableConfigurationProperties(OpenAiAsrProperties.class)
public class OpenAiAsrAutoConfiguration {
    /**
     * 默认 ASR 工厂，业务侧声明同类型 Bean 后会自动替换。
     */
    @Bean
    @ConditionalOnMissingBean(StreamingAsrAdapterFactory.class)
    public StreamingAsrAdapterFactory streamingAsrAdapterFactory(OpenAiAsrProperties asrProperties,
                                                                 ObjectMapper objectMapper) {
        return new OpenAiStreamingAsrAdapterFactory(asrProperties, objectMapper);
    }
}
