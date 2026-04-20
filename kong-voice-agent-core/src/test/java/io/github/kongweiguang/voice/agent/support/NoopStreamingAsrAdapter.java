package io.github.kongweiguang.voice.agent.support;

import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapter;

import java.util.Optional;

/**
 * core 测试专用的最小 ASR stub，避免测试依赖 app 模块里的 mock 实现。
 *
 * @author kongweiguang
 */
public class NoopStreamingAsrAdapter implements StreamingAsrAdapter {
    @Override
    public Optional<AsrUpdate> acceptAudio(long turnId, byte[] pcm) {
        return Optional.empty();
    }

    @Override
    public AsrUpdate commitTurn(long turnId) {
        return AsrUpdate.finalUpdate(turnId, "");
    }

    @Override
    public void close() {
    }
}
