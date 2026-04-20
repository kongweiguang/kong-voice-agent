package io.github.kongweiguang.voice.agent.config;

import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapterFactory;
import io.github.kongweiguang.voice.agent.llm.LlmOrchestrator;
import io.github.kongweiguang.voice.agent.mock.asr.MockStreamingAsrAdapterFactory;
import io.github.kongweiguang.voice.agent.mock.llm.MockLlmOrchestrator;
import io.github.kongweiguang.voice.agent.mock.tts.MockTtsOrchestrator;
import io.github.kongweiguang.voice.agent.tts.TtsOrchestrator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * app 模块的 mock 能力装配。真实业务接入时声明同类型 Bean 即可覆盖这些默认实现。
 *
 * @author kongweiguang
 */
@Configuration
public class MockVoiceCapabilityConfig {
    /**
     * 默认 mock ASR 工厂，业务方声明同类型 Bean 后自动让位。
     */
    @Bean
    @ConditionalOnMissingBean
    public StreamingAsrAdapterFactory streamingAsrAdapterFactory() {
        return new MockStreamingAsrAdapterFactory();
    }

    /**
     * 默认 mock LLM，保证开源用户无需外部服务即可验证闭环。
     */
    @Bean
    @ConditionalOnMissingBean
    public LlmOrchestrator llmOrchestrator() {
        return new MockLlmOrchestrator();
    }

    /**
     * 默认 mock TTS，输出确定性字节用于协议联调。
     */
    @Bean
    @ConditionalOnMissingBean
    public TtsOrchestrator ttsOrchestrator() {
        return new MockTtsOrchestrator();
    }
}
