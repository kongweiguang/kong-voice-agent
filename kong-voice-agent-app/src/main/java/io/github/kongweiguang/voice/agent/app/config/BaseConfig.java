package io.github.kongweiguang.voice.agent.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * app 模块的基础运行配置，负责线程执行器装配。
 *
 * @author kongweiguang
 */
@Configuration
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
     * 创建带固定名称前缀的虚拟线程执行器，便于日志和诊断区分任务来源。
     */
    private ExecutorService virtualExecutor(String prefix) {
        ThreadFactory factory = Thread.ofVirtual().name(prefix, 0).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }
}
