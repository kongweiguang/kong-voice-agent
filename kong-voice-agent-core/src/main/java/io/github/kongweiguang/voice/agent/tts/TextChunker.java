package io.github.kongweiguang.voice.agent.tts;

import java.util.ArrayList;
import java.util.List;

/**
 * 按标点或长度将 LLM 文本切成适合 TTS 的小片段。
 *
 * @author kongweiguang
 */
public class TextChunker {
    /**
     * 单个 TTS 文本块的最大字符数，避免 mock 或真实合成一次处理过长文本。
     */
    private static final int MAX_CHARS = 48;

    /**
     * 将 LLM 文本切分为适合逐块合成和下发的片段。
     */
    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            current.append(ch);
            if (isBoundary(ch) || current.length() >= MAX_CHARS) {
                chunks.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /**
     * 类句子标点会被视为立即合成边界。
     */
    private boolean isBoundary(char ch) {
        return ch == '。' || ch == '，' || ch == ',' || ch == '?' || ch == '？' || ch == '!' || ch == '！';
    }
}
