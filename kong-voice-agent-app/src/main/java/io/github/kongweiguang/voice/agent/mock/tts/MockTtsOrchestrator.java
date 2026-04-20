package io.github.kongweiguang.voice.agent.mock.tts;

import io.github.kongweiguang.voice.agent.tts.TextChunker;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;
import io.github.kongweiguang.voice.agent.tts.TtsOrchestrator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * app 模块内的 TTS 模拟实现，将文本编码为确定性字节以便协议测试。
 *
 * @author kongweiguang
 */
public class MockTtsOrchestrator implements TtsOrchestrator {
    /**
     * 复用核心文本切分策略，让 mock TTS 和真实 TTS 接入前的事件粒度一致。
     */
    private final TextChunker textChunker = new TextChunker();

    /**
     * 将文本片段转为确定性字节块，模拟真实 TTS 的有序音频输出。
     */
    @Override
    public List<TtsChunk> synthesize(long turnId, int startSeq, String text, boolean lastTextChunk) {
        List<String> textChunks = textChunker.split(text);
        List<TtsChunk> out = new ArrayList<>();
        for (int i = 0; i < textChunks.size(); i++) {
            boolean last = lastTextChunk && i == textChunks.size() - 1;
            byte[] audio = ("MOCK_PCM:" + textChunks.get(i)).getBytes(StandardCharsets.UTF_8);
            out.add(new TtsChunk(turnId, startSeq + i, last, audio, textChunks.get(i)));
        }
        return out;
    }
}
