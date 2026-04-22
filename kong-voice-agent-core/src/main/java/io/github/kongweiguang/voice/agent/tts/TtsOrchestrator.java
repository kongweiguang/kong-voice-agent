package io.github.kongweiguang.voice.agent.tts;

import java.util.List;
import java.util.function.Consumer;

/**
 * 文本转语音的可替换边界，采用 Strategy/Adapter 模式支持模拟或真实合成。
 *
 * @author kongweiguang
 */
public interface TtsOrchestrator {
    /**
     * 将一个 LLM 文本片段合成为一个或多个有序音频块。
     */
    List<TtsChunk> synthesize(String turnId, int startSeq, String text, boolean lastTextChunk);

    /**
     * 将一个 LLM 文本片段合成为流式音频块；默认实现复用非流式接口，兼容已有业务 Bean。
     */
    default void synthesizeStreaming(String turnId, int startSeq, String text, boolean lastTextChunk,
                                     Consumer<TtsChunk> chunkConsumer) {
        synthesize(turnId, startSeq, text, lastTextChunk).forEach(chunkConsumer);
    }
}
