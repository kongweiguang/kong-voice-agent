package io.github.kongweiguang.voice.autoconfigure;

import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.eou.EouDetector;
import io.github.kongweiguang.voice.agent.eou.EouHistoryProvider;
import io.github.kongweiguang.voice.agent.eou.livekit.MultilingualEouDetector;
import io.github.kongweiguang.voice.agent.eou.NoopEouDetector;
import io.github.kongweiguang.voice.agent.onnx.OnnxRuntimeConfig;
import io.github.kongweiguang.voice.agent.onnx.OnnxSessionOptionsFactory;
import io.github.kongweiguang.voice.agent.vad.SileroVadEngine;
import io.github.kongweiguang.voice.agent.vad.VadConfig;
import io.github.kongweiguang.voice.agent.vad.VadEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

/**
 * voice 公共模块的 VAD 与 EOU 默认装配。ASR、LLM、TTS 的 mock 或业务实现由 app 模块负责提供。
 *
 * @author kongweiguang
 */
@AutoConfiguration
@EnableConfigurationProperties({AudioFormatSpec.class, VadConfig.class, EouConfig.class, OnnxRuntimeConfig.class})
public class VoiceDefaultAutoConfiguration {
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
        if (!config.enabled()) {
            return new NoopEouDetector();
        }
        return new MultilingualEouDetector(config, historyProvider, resourceLoader, sessionOptionsFactory);
    }
}
