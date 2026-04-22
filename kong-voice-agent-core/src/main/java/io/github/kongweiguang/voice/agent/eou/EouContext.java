package io.github.kongweiguang.voice.agent.eou;

import java.util.List;

/**
 * EOU 判断输入，隔离模型实现对会话内部状态的直接依赖。
 *
 * @param sessionId 当前语音会话标识
 * @param turnId 当前用户 turn 标识
 * @param currentTranscript 当前 turn 已识别到的 ASR 文本
 * @param language 当前 turn 的语言标识
 * @param silenceMs 当前静音候选已持续的毫秒数
 * @author kongweiguang
 */
public record EouContext(
        String sessionId,
        String turnId,
        String currentTranscript,
        String language,
        long silenceMs) {
    /**
     * 归一化文本和集合，避免具体 detector 重复判空。
     */
    public EouContext {
        sessionId = sessionId == null ? "" : sessionId;
        currentTranscript = currentTranscript == null ? "" : currentTranscript.trim();
        language = language == null || language.isBlank() ? "zh" : language.trim();
    }
}
