package io.github.kongweiguang.voice.agent.vad;

/**
 * 音频接入流水线使用的语音活动检测器，采用 Strategy 模式支持 ONNX、RMS 或业务自定义实现互换。
 *
 * @author kongweiguang
 */
public interface VadEngine extends AutoCloseable {
    /**
     * 将 PCM 窗口转换为说话概率和二值说话标记。
     */
    VadDecision detect(String turnId, byte[] pcm);

    /**
     * 表示当前引擎是否使用 ONNX 模型而非兜底逻辑。
     */
    boolean modelBacked();

    @Override
    void close();
}
