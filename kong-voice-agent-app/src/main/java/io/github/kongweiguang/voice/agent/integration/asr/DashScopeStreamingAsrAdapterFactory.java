package io.github.kongweiguang.voice.agent.integration.asr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapterFactory;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import lombok.RequiredArgsConstructor;

/**
 * app 默认 ASR 工厂，为每个会话创建独立 DashScope Qwen-ASR 适配器。
 *
 * @author kongweiguang
 */
@RequiredArgsConstructor
public class DashScopeStreamingAsrAdapterFactory implements StreamingAsrAdapterFactory {
    /**
     * DashScope Qwen-ASR 服务配置。
     */
    private final DashScopeAsrProperties properties;

    /**
     * 共享 JSON 解析器。
     */
    private final ObjectMapper objectMapper;

    /**
     * 为每个会话创建独立 ASR 适配器，避免音频累计状态串线。
     */
    @Override
    public StreamingAsrAdapter create(String sessionId, AudioFormatSpec format) {
        return new DashScopeStreamingAsrAdapter(format, properties, objectMapper);
    }
}
