package io.github.kongweiguang.voice.agent.audio;

/**
 * 流水线接受的 PCM 格式不可变描述。
 *
 * @author kongweiguang
 */
public record AudioFormatSpec(
        /**
         * 音频采样率，单位为 Hz。
         */
        int sampleRate,

        /**
         * 音频声道数，当前默认使用单声道。
         */
        int channels,

        /**
         * PCM 采样格式，当前协议固定为 s16le。
         */
        String sampleFormat,

        /**
         * 客户端建议上传单个音频块的时长，单位为毫秒。
         */
        int uploadChunkMs) {
    /**
     * 默认音频格式：16kHz、单声道、16 位 little-endian PCM、20ms 每包。
     */
    public static final AudioFormatSpec DEFAULT = new AudioFormatSpec(16000, 1, "s16le", 20);

    /**
     * 固定 s16le PCM 每个采样点占用的字节数。
     */
    public int bytesPerSample() {
        return 2;
    }

    /**
     * 计算固定 s16le PCM 音频每毫秒对应的字节数。
     */
    public int bytesPerMillisecond() {
        return sampleRate * channels * bytesPerSample() / 1000;
    }

    /**
     * 将毫秒时长换算为该格式下的 PCM 字节数。
     */
    public int bytesForMs(int ms) {
        return ms * bytesPerMillisecond();
    }

    /**
     * 将毫秒时长换算为单声道采样点数量。
     */
    public int samplesForMs(int ms) {
        return sampleRate * ms / 1000;
    }
}
