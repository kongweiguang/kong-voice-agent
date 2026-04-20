package io.github.kongweiguang.voice.agent.audio;

/**
 * 维护说话开始前的一小段音频窗口，供后续 ASR/VAD 使用。
 *
 * @author kongweiguang
 */
public class PreRollBuffer {
    /**
     * 保存最近 pre-roll 音频窗口的环形缓冲区。
     */
    private final CircularByteBuffer buffer;

    /**
     * 根据音频格式和预滚时长创建缓冲区。
     */
    public PreRollBuffer(AudioFormatSpec format, int preRollMs) {
        this.buffer = new CircularByteBuffer(format.bytesForMs(preRollMs));
    }

    /**
     * 写入最新 PCM 音频块。
     */
    public void write(byte[] pcm) {
        buffer.write(pcm);
    }

    /**
     * 获取当前预滚窗口快照。
     */
    public byte[] snapshot() {
        return buffer.snapshot();
    }

    /**
     * 清空预滚窗口。
     */
    public void clear() {
        buffer.clear();
    }
}
