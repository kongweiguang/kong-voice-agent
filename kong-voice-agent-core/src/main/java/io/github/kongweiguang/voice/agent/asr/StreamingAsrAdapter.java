package io.github.kongweiguang.voice.agent.asr;

import java.util.Optional;

/**
 * 流式语音识别的可替换边界，支持模拟或真实实现。
 *
 * @author kongweiguang
 */
public interface StreamingAsrAdapter extends AutoCloseable {
    /**
     * 将音频送入 ASR，并按需产出局部转写。
     */
    Optional<AsrUpdate> acceptAudio(long turnId, byte[] pcm);

    /**
     * 为已提交的 turn 完成最终识别。
     */
    AsrUpdate commitTurn(long turnId);

    @Override
    void close();
}
