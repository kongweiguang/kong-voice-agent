package io.github.kongweiguang.voice.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapterFactory;
import io.github.kongweiguang.voice.agent.integration.asr.DashScopeAsrProperties;
import io.github.kongweiguang.voice.agent.integration.asr.DashScopeStreamingAsrAdapterFactory;
import io.github.kongweiguang.voice.agent.integration.tts.DashScopeTtsOrchestrator;
import io.github.kongweiguang.voice.agent.integration.tts.DashScopeTtsProperties;
import io.github.kongweiguang.voice.agent.tts.TtsOrchestrator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * app 模块的默认能力装配。真实业务接入时声明同类型 Bean 即可覆盖这些默认实现。
 *
 * @author kongweiguang
 */
@Configuration
@EnableConfigurationProperties({DashScopeAsrProperties.class, DashScopeTtsProperties.class})
public class BaseConfig {

    /**
     * 音频处理任务执行器，避免 VAD/ASR/TurnManager 阻塞 WebSocket IO 线程。
     */
    @Bean(name = "audioTaskExecutor", destroyMethod = "shutdown")
    public ExecutorService audioTaskExecutor() {
        return virtualExecutor("audio-vt-");
    }

    /**
     * Agent 下游任务执行器，承载 LLM 和 TTS 的异步处理。
     */
    @Bean(name = "agentTaskExecutor", destroyMethod = "shutdown")
    public ExecutorService agentTaskExecutor() {
        return virtualExecutor("agent-vt-");
    }

    /**
     * 默认 ASR 工厂，直接对接 DashScope Qwen-ASR，业务侧声明同类型 Bean 后会自动替换。
     */
    @Bean
    @ConditionalOnMissingBean(StreamingAsrAdapterFactory.class)
    public StreamingAsrAdapterFactory streamingAsrAdapterFactory(DashScopeAsrProperties asrProperties, ObjectMapper objectMapper) {
        return new DashScopeStreamingAsrAdapterFactory(asrProperties, objectMapper);
    }

    /**
     * 默认 TTS 编排器，直接对接 DashScope Qwen-TTS，业务侧声明同类型 Bean 后会自动替换。
     */
    @Bean
    @ConditionalOnMissingBean(TtsOrchestrator.class)
    public TtsOrchestrator ttsOrchestrator(DashScopeTtsProperties ttsProperties, ObjectMapper objectMapper) {
        return new DashScopeTtsOrchestrator(ttsProperties, objectMapper);
    }

    /**
     * 创建带固定名称前缀的虚拟线程执行器，便于日志和诊断区分任务来源。
     */
    private ExecutorService virtualExecutor(String prefix) {
        ThreadFactory factory = Thread.ofVirtual().name(prefix, 0).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }
}
