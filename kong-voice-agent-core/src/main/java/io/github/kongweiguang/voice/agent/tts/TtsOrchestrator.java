package io.github.kongweiguang.voice.agent.tts;

import java.util.List;

/**
 * 文本转语音的可替换边界，采用 Strategy/Adapter 模式支持模拟或真实合成。
 *
 * @author kongweiguang
 */
public interface TtsOrchestrator {
    /**
     * 将一个 LLM 文本片段合成为一个或多个有序音频块。
     */
    List<TtsChunk> synthesize(long turnId, int startSeq, String text, boolean lastTextChunk);
}
