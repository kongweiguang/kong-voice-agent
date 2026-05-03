package io.github.kongweiguang.voice.agent.extension.tts.qwen;

/**
 * Qwen TTS Realtime 会话的最小操作边界。
 *
 * @author kongweiguang
 */
public interface QwenTtsRealtimeSession {
    /**
     * 建立实时 TTS WebSocket 连接并更新会话配置。
     */
    void connect();

    /**
     * 向服务端文本缓冲区追加一段文本。
     *
     * @param text 待合成文本
     */
    void appendText(String text);

    /**
     * 在 commit 模式下提交服务端文本缓冲区并触发合成。
     */
    void commit();

    /**
     * 结束当前实时 TTS 任务。
     */
    void finish();

    /**
     * 关闭底层 WebSocket 连接。
     */
    void close();
}
