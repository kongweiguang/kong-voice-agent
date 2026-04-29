package io.github.kongweiguang.voice.agent.extension.transport.rtc;

import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import io.github.kongweiguang.voice.agent.media.AudioEgressAdapter;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 将 TTS 音频写入 WebRTC 自定义音频源，作为远端下行音轨回放。
 *
 * @author kongweiguang
 */
public class RtcAudioEgressAdapter implements AudioEgressAdapter {
    /**
     * WebRTC 下行自定义音频源。
     */
    private final CustomAudioSource customAudioSource;

    /**
     * 串行播放线程，保证同一会话内 TTS 音频顺序稳定。
     */
    private final ExecutorService playbackExecutor;

    /**
     * 播放代次；打断时递增，让旧任务自行失效退出。
     */
    private final AtomicLong playbackGeneration = new AtomicLong();

    /**
     * 创建 RTC 音频下行适配器。
     */
    public RtcAudioEgressAdapter(String sessionId, CustomAudioSource customAudioSource) {
        this.customAudioSource = customAudioSource;
        ThreadFactory factory = Thread.ofVirtual().name("rtc-egress-" + sessionId + "-", 0).factory();
        this.playbackExecutor = Executors.newSingleThreadExecutor(factory);
    }

    /**
     * RTC 适配器创建成功即视为可用。
     */
    @Override
    public boolean available() {
        return true;
    }

    /**
     * 将当前 TTS 音频块按 10ms 分片推送给 WebRTC 音频源。
     */
    @Override
    public void onTtsChunk(SessionState session, TtsChunk chunk) {
        long generation = playbackGeneration.get();
        playbackExecutor.execute(() -> playChunk(session, chunk, generation));
    }

    /**
     * 打断时让旧的播放任务全部失效。
     */
    @Override
    public void onPlaybackStop(SessionState session, String turnId, String reason) {
        playbackGeneration.incrementAndGet();
    }

    /**
     * 释放播放线程与相关资源。
     */
    @Override
    public void close() {
        playbackGeneration.incrementAndGet();
        playbackExecutor.shutdownNow();
    }

    /**
     * 实际播放单个 TTS 音频块。
     */
    private void playChunk(SessionState session, TtsChunk chunk, long generation) {
        if (!session.isCurrentTurn(chunk.turnId()) || generation != playbackGeneration.get()) {
            return;
        }
        try {
            DecodedPcmAudio decoded = decodeAudio(chunk.audio());
            int bytesPerFrame = decoded.channels() * (decoded.bitsPerSample() / 8);
            int framesPer10Ms = Math.max(1, decoded.sampleRate() / 100);
            int bytesPer10Ms = framesPer10Ms * bytesPerFrame;
            for (int offset = 0; offset < decoded.pcm().length; offset += bytesPer10Ms) {
                if (!session.isCurrentTurn(chunk.turnId()) || generation != playbackGeneration.get()) {
                    return;
                }
                int size = Math.min(bytesPer10Ms, decoded.pcm().length - offset);
                byte[] frame = new byte[size];
                System.arraycopy(decoded.pcm(), offset, frame, 0, size);
                customAudioSource.pushAudio(frame, decoded.bitsPerSample(), decoded.sampleRate(), decoded.channels(), size / bytesPerFrame);
                Thread.sleep(10L);
            }
        } catch (Exception ignored) {
            // 当前适配器属于播放旁路，不应因某个 chunk 解码失败影响控制面事件发送。
        }
    }

    /**
     * 优先按 WAV 解码；若远端返回的是裸 PCM，则按当前前端兜底约定 24kHz / mono / 16-bit 处理。
     */
    private DecodedPcmAudio decodeAudio(byte[] audio) throws IOException, UnsupportedAudioFileException {
        try (AudioInputStream sourceStream = AudioSystem.getAudioInputStream(
                new BufferedInputStream(new ByteArrayInputStream(audio)))) {
            AudioFormat sourceFormat = sourceStream.getFormat();
            AudioFormat pcmFormat = toPcmSigned(sourceFormat);
            try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcmFormat, sourceStream)) {
                return new DecodedPcmAudio(
                        pcmStream.readAllBytes(),
                        (int) pcmFormat.getSampleRate(),
                        pcmFormat.getChannels(),
                        pcmFormat.getSampleSizeInBits()
                );
            }
        } catch (UnsupportedAudioFileException ex) {
            return new DecodedPcmAudio(audio, 24_000, 1, 16);
        }
    }

    /**
     * 将任意可解码格式统一转换成 little-endian PCM_SIGNED。
     */
    private AudioFormat toPcmSigned(AudioFormat sourceFormat) {
        if (AudioFormat.Encoding.PCM_SIGNED.equals(sourceFormat.getEncoding())
                && sourceFormat.getSampleSizeInBits() == 16
                && !sourceFormat.isBigEndian()) {
            return sourceFormat;
        }
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sourceFormat.getSampleRate(),
                16,
                sourceFormat.getChannels(),
                sourceFormat.getChannels() * 2,
                sourceFormat.getSampleRate(),
                false
        );
    }

    /**
     * 解码后的 PCM 描述。
     *
     * @param pcm           little-endian PCM 数据
     * @param sampleRate    采样率
     * @param channels      声道数
     * @param bitsPerSample 位深
     * @author kongweiguang
     */
    private record DecodedPcmAudio(byte[] pcm,
                                   int sampleRate,
                                   int channels,
                                   int bitsPerSample) {
    }
}
