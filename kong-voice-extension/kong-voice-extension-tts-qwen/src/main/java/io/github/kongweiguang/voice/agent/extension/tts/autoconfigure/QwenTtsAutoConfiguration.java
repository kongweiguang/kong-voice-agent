package io.github.kongweiguang.voice.agent.extension.tts.autoconfigure;

import io.github.kongweiguang.voice.agent.extension.tts.qwen.QwenTtsOrchestrator;
import io.github.kongweiguang.voice.agent.extension.tts.qwen.QwenTtsProperties;
import io.github.kongweiguang.voice.agent.extension.tts.qwen.DashScopeQwenTtsRealtimeSessionFactory;
import io.github.kongweiguang.voice.agent.extension.tts.qwen.QwenTtsRealtimeSessionFactory;
import io.github.kongweiguang.voice.agent.tts.TtsOrchestrator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Qwen TTS 扩展自动配置，默认向外暴露 TTS 编排器。
 *
 * @author kongweiguang
 */
@AutoConfiguration
@EnableConfigurationProperties(QwenTtsProperties.class)
public class QwenTtsAutoConfiguration {
    /**
     * 默认 TTS 编排器，业务侧声明同类型 Bean 后会自动替换。
     */
    @Bean
    @ConditionalOnMissingBean(TtsOrchestrator.class)
    public TtsOrchestrator ttsOrchestrator(QwenTtsProperties ttsProperties,
                                           QwenTtsRealtimeSessionFactory sessionFactory) {
        return new QwenTtsOrchestrator(ttsProperties, sessionFactory);
    }

    /**
     * 默认 Qwen TTS Realtime 会话工厂，测试或业务侧可以替换为自定义实现。
     */
    @Bean
    @ConditionalOnMissingBean(QwenTtsRealtimeSessionFactory.class)
    public QwenTtsRealtimeSessionFactory qwenTtsRealtimeSessionFactory() {
        return new DashScopeQwenTtsRealtimeSessionFactory();
    }
}
