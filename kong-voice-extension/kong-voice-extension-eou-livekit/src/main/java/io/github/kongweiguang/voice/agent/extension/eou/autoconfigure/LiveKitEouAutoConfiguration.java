package io.github.kongweiguang.voice.agent.extension.eou.autoconfigure;

import io.github.kongweiguang.voice.agent.extension.eou.livekit.MultilingualEouDetector;
import io.github.kongweiguang.voice.agent.extension.vad.silero.OnnxSessionOptionsFactory;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.eou.EouDetector;
import io.github.kongweiguang.voice.agent.eou.EouHistoryProvider;
import io.github.kongweiguang.voice.agent.eou.NoopEouDetector;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

/**
 * LiveKit 风格 EOU 扩展自动配置，负责提供默认历史读取器和默认 detector。
 *
 * @author kongweiguang
 */
@AutoConfiguration
public class LiveKitEouAutoConfiguration {
    /**
     * 默认 EOU 历史读取器。业务方可通过 Bean 提供按会话查询历史的能力。
     */
    @Bean
    @ConditionalOnMissingBean
    public EouHistoryProvider eouHistoryProvider() {
        return EouHistoryProvider.empty();
    }

    /**
     * 默认 EOU detector。业务方声明自己的 EouDetector 后，该默认实现自动让位。
     */
    @Bean
    @ConditionalOnMissingBean
    public EouDetector eouDetector(EouConfig config,
                                   EouHistoryProvider historyProvider,
                                   ResourceLoader resourceLoader,
                                   OnnxSessionOptionsFactory sessionOptionsFactory) {
        if (!Boolean.TRUE.equals(config.enabled())) {
            return new NoopEouDetector();
        }
        return new MultilingualEouDetector(config, historyProvider, resourceLoader, sessionOptionsFactory);
    }
}
