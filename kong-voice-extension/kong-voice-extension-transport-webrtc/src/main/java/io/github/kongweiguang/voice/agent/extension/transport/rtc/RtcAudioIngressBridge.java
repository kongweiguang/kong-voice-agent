package io.github.kongweiguang.voice.agent.extension.transport.rtc;

import dev.onvoid.webrtc.media.audio.AudioConverter;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.service.VoicePipelineService;
import io.github.kongweiguang.voice.agent.session.SessionState;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将 RTC 入站音轨转换为核心流水线可消费的标准 PCM。
 *
 * @author kongweiguang
 */
@RequiredArgsConstructor
public class RtcAudioIngressBridge implements AudioTrackSink, AutoCloseable {
    /**
     * 当前会话状态。
     */
    private final SessionState session;

    /**
     * 核心流水线门面。
     */
    private final VoicePipelineService voicePipelineService;

    /**
     * 首包音频真正流入后端时触发的回调，用于补充 RTC 可观测状态。
     */
    private final Runnable onFirstAudioFrame;

    /**
     * 最近一次音频格式对应的转换器；只有源格式变化时才重新创建。
     */
    private volatile AudioConverter audioConverter;

    /**
     * 当前转换器对应的源采样率。
     */
    private volatile Integer sourceSampleRate;

    /**
     * 当前转换器对应的源声道数。
     */
    private volatile Integer sourceChannels;

    /**
     * 首包音频只需要上报一次，避免后续每帧都重复写状态事件。
     */
    private final AtomicBoolean firstAudioFrameReported = new AtomicBoolean(false);

    /**
     * 收到远端音轨 PCM 后，必要时重采样到当前后端标准音频格式。
     */
    @Override
    public void onData(byte[] audio, int bitsPerSample, int sampleRate, int channels, int frames) {
        if (audio == null || audio.length == 0 || bitsPerSample != 16) {
            return;
        }
        if (firstAudioFrameReported.compareAndSet(false, true) && onFirstAudioFrame != null) {
            onFirstAudioFrame.run();
        }
        voicePipelineService.acceptAudio(session, normalize(audio, sampleRate, channels));
    }

    /**
     * 释放转换器资源。
     */
    @Override
    public void close() {
        AudioConverter current = audioConverter;
        if (current != null) {
            current.dispose();
            audioConverter = null;
        }
    }

    /**
     * 按 `AudioFormatSpec` 归一化当前 RTC 音频块。
     */
    private byte[] normalize(byte[] audio, int sampleRate, int channels) {
        AudioFormatSpec format = session.audioFormatSpec();
        if (sampleRate == format.sampleRate() && channels == format.channels()) {
            return Arrays.copyOf(audio, audio.length);
        }
        AudioConverter converter = ensureAudioConverter(sampleRate, channels, format);
        byte[] target = new byte[converter.getTargetBufferSize()];
        converter.convert(audio, target);
        return target;
    }

    /**
     * 当 RTC 源格式变化时重新创建转换器。
     */
    private synchronized AudioConverter ensureAudioConverter(int sampleRate, int channels, AudioFormatSpec targetFormat) {
        if (audioConverter != null
                && Integer.valueOf(sampleRate).equals(sourceSampleRate)
                && Integer.valueOf(channels).equals(sourceChannels)) {
            return audioConverter;
        }
        if (audioConverter != null) {
            audioConverter.dispose();
        }
        audioConverter = new AudioConverter(sampleRate, channels, targetFormat.sampleRate(), targetFormat.channels());
        sourceSampleRate = sampleRate;
        sourceChannels = channels;
        return audioConverter;
    }
}
