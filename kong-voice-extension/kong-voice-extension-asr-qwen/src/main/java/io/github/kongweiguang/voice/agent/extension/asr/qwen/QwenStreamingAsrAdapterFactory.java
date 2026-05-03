package io.github.kongweiguang.voice.agent.extension.asr.qwen;

import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapterFactory;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import lombok.RequiredArgsConstructor;

/**
 * Qwen ASR 扩展默认工厂，为每个会话创建独立 ASR 适配器。
 *
 * @author kongweiguang
 */
@RequiredArgsConstructor
public class QwenStreamingAsrAdapterFactory implements StreamingAsrAdapterFactory {
    /**
     * Qwen ASR 服务配置。
     */
    private final QwenAsrProperties properties;

    /**
     * Qwen Realtime 会话工厂。
     */
    private final QwenRealtimeSessionFactory sessionFactory;

    /**
     * 创建使用 DashScope SDK 的默认工厂。
     */
    public QwenStreamingAsrAdapterFactory(QwenAsrProperties properties) {
        this(properties, new DashScopeQwenRealtimeSessionFactory());
    }

    /**
     * 为每个会话创建独立 ASR 适配器，避免音频累计状态串线。
     */
    @Override
    public StreamingAsrAdapter create(String sessionId, AudioFormatSpec format) {
        return new QwenStreamingAsrAdapter(format, properties, sessionFactory);
    }
}
