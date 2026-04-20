package io.github.kongweiguang.voice.agent.mock.asr;

import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapterFactory;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;

/**
 * app 默认 ASR 工厂。未接入真实 ASR 时，用 mock 实现保证链路可运行。
 *
 * @author kongweiguang
 */
public class MockStreamingAsrAdapterFactory implements StreamingAsrAdapterFactory {
    /**
     * 为每个会话创建独立 mock ASR，避免音频累计状态串线。
     */
    @Override
    public StreamingAsrAdapter create(String sessionId, AudioFormatSpec format) {
        return new MockStreamingAsrAdapter(format);
    }
}
