package io.github.kongweiguang.voice.agent.extension.asr.qwen;

/**
 * Qwen-ASR-Realtime 会话抽象，用于隔离 DashScope SDK 连接细节并方便测试替换。
 *
 * @author kongweiguang
 */
public interface QwenRealtimeSession extends AutoCloseable {
    /**
     * 建立实时识别 WebSocket 连接并完成会话配置。
     */
    void connect();

    /**
     * 追加 Base64 编码后的音频片段。
     */
    void appendAudio(String audioBase64);

    /**
     * 手动提交服务端输入音频缓冲区。
     */
    void commit();

    /**
     * 通知服务端结束当前识别会话。
     */
    void endSession();

    /**
     * 关闭连接并释放 SDK 运行态。
     */
    @Override
    void close();
}
