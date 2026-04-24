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
    Optional<AsrUpdate> acceptAudio(String turnId, byte[] pcm);

    /**
     * 为已提交的 turn 完成最终识别。
     */
    AsrUpdate commitTurn(String turnId);

    /**
     * 取消指定 turn 的 ASR 累计状态。默认无操作，真实适配器可释放缓存或关闭远端流。
     */
    default void cancelTurn(String turnId) {
    }

    @Override
    void close();
}
