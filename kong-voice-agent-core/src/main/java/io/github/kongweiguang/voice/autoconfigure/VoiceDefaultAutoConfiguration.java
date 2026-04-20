package io.github.kongweiguang.voice.autoconfigure;

import io.github.kongweiguang.voice.agent.vad.SileroVadEngine;
import io.github.kongweiguang.voice.agent.vad.VadConfig;
import io.github.kongweiguang.voice.agent.vad.VadEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

/**
 * voice 公共模块的 VAD 默认装配。ASR、LLM、TTS 的 mock 或业务实现由 app 模块负责提供。
 *
 * @author kongweiguang
 */
@AutoConfiguration
@EnableConfigurationProperties(VadConfig.class)
public class VoiceDefaultAutoConfiguration {
    /**
     * 默认 VAD Bean。业务方声明自己的 VadEngine 后，该默认实现自动让位。
     */
    @Bean
    @ConditionalOnMissingBean
    public VadEngine vadEngine(VadConfig config, ResourceLoader resourceLoader) {
        return new SileroVadEngine(config, resourceLoader);
    }
}
