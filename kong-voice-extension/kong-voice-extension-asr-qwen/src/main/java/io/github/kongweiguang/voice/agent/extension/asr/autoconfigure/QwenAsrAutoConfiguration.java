package io.github.kongweiguang.voice.agent.extension.asr.autoconfigure;

import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapterFactory;
import io.github.kongweiguang.voice.agent.extension.asr.qwen.QwenAsrProperties;
import io.github.kongweiguang.voice.agent.extension.asr.qwen.QwenStreamingAsrAdapterFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Qwen ASR 扩展自动配置，显式启用后向外暴露 ASR 工厂。
 *
 * @author kongweiguang
 */
@AutoConfiguration
@AutoConfigureBefore(name = "io.github.kongweiguang.voice.agent.extension.asr.autoconfigure.OpenAiAsrAutoConfiguration")
@EnableConfigurationProperties(QwenAsrProperties.class)
@ConditionalOnProperty(prefix = "kong-voice-agent.asr.qwen", name = "enabled", havingValue = "true")
public class QwenAsrAutoConfiguration {
    /**
     * 默认 ASR 工厂，业务侧声明同类型 Bean 后会自动替换。
     */
    @Bean
    @ConditionalOnMissingBean(StreamingAsrAdapterFactory.class)
    public StreamingAsrAdapterFactory qwenStreamingAsrAdapterFactory(QwenAsrProperties asrProperties) {
        return new QwenStreamingAsrAdapterFactory(asrProperties);
    }
}
