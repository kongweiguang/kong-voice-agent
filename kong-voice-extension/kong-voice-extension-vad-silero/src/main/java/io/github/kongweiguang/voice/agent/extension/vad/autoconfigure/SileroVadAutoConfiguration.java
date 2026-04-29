package io.github.kongweiguang.voice.agent.extension.vad.autoconfigure;

import io.github.kongweiguang.voice.agent.extension.vad.silero.OnnxRuntimeConfig;
import io.github.kongweiguang.voice.agent.extension.vad.silero.OnnxSessionOptionsFactory;
import io.github.kongweiguang.voice.agent.extension.vad.silero.SileroVadEngine;
import io.github.kongweiguang.voice.agent.extension.vad.silero.VadConfig;
import io.github.kongweiguang.voice.agent.vad.VadEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

/**
 * Silero VAD 扩展自动配置，负责提供默认 VAD 与 ONNX Runtime 装配。
 *
 * @author kongweiguang
 */
@AutoConfiguration
@EnableConfigurationProperties({VadConfig.class, OnnxRuntimeConfig.class})
public class SileroVadAutoConfiguration {
    /**
     * ONNX 会话选项工厂。业务方可覆盖该 Bean 以接入更多执行提供方或高级参数。
     */
    @Bean
    @ConditionalOnMissingBean
    public OnnxSessionOptionsFactory onnxSessionOptionsFactory(OnnxRuntimeConfig config) {
        return new OnnxSessionOptionsFactory(config);
    }

    /**
     * 默认 VAD Bean。业务方声明自己的 VadEngine 后，该默认实现自动让位。
     */
    @Bean
    @ConditionalOnMissingBean
    public VadEngine vadEngine(VadConfig config,
                               ResourceLoader resourceLoader,
                               OnnxSessionOptionsFactory sessionOptionsFactory) {
        return new SileroVadEngine(config, resourceLoader, sessionOptionsFactory);
    }
}
