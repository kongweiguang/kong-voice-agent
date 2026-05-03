package io.github.kongweiguang.voice.agent.extension.asr.openai;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapterFactory;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import lombok.RequiredArgsConstructor;

/**
 * OpenAI ASR 扩展默认工厂，为每个会话创建独立 ASR 适配器。
 *
 * @author kongweiguang
 */
@RequiredArgsConstructor
public class OpenAiStreamingAsrAdapterFactory implements StreamingAsrAdapterFactory {
    /**
     * OpenAI ASR 服务配置。
     */
    private final OpenAiAsrProperties properties;

    /**
     * 为每个会话创建独立 ASR 适配器，避免音频累计状态串线。
     */
    @Override
    public StreamingAsrAdapter create(String sessionId, AudioFormatSpec format) {
        return new OpenAiStreamingAsrAdapter(format, properties);
    }
}
