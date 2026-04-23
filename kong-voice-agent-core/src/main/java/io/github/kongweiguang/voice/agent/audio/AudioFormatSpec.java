package io.github.kongweiguang.voice.agent.audio;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 流水线接受的 PCM 格式不可变描述，同时承载 kong-voice-agent.audio 外部配置绑定。
 *
 * @param sampleRate    音频采样率，单位为 Hz
 * @param channels      音频声道数，当前默认使用单声道
 * @param sampleFormat  PCM 采样格式，当前协议固定为 s16le
 * @param uploadChunkMs 客户端建议上传单个音频块的时长，单位为毫秒
 * @author kongweiguang
 */
@ConfigurationProperties(prefix = "kong-voice-agent.audio")
public record AudioFormatSpec(Integer sampleRate, Integer channels, String sampleFormat, Integer uploadChunkMs) {
    /**
     * 默认音频格式：16kHz、单声道、16 位 little-endian PCM、20ms 每包。
     */
    public static final AudioFormatSpec DEFAULT = new AudioFormatSpec(16000, 1, "s16le", 20);

    /**
     * 归一化外部配置，保证未配置或错误配置时仍使用协议默认音频格式。
     */
    public AudioFormatSpec {
        if (sampleRate == null || sampleRate <= 0) {
            sampleRate = DEFAULT.sampleRate();
        }
        if (channels == null || channels <= 0) {
            channels = DEFAULT.channels();
        }
        if (sampleFormat == null || sampleFormat.isBlank()) {
            sampleFormat = DEFAULT.sampleFormat();
        }
        if (uploadChunkMs == null || uploadChunkMs <= 0) {
            uploadChunkMs = DEFAULT.uploadChunkMs();
        }
    }

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
