package io.github.kongweiguang.voice.agent.audio;

import java.util.Arrays;

/**
 * 固定 16kHz 单声道 s16le PCM 格式的工具方法。
 *
 * @author kongweiguang
 */
public final class PcmUtils {
    /**
     * 工具类不允许实例化。
     */
    private PcmUtils() {
    }

    /**
     * 将 little-endian PCM16 字节转换为有符号 short 采样。
     */
    public static short[] littleEndianBytesToShorts(byte[] pcm) {
        if (pcm.length % 2 != 0) {
            throw new IllegalArgumentException("16-bit PCM must contain an even number of bytes");
        }
        short[] samples = new short[pcm.length / 2];
        for (int i = 0; i < samples.length; i++) {
            int lo = pcm[i * 2] & 0xff;
            int hi = pcm[i * 2 + 1] << 8;
            samples[i] = (short) (hi | lo);
        }
        return samples;
    }

    /**
     * 为有符号 16 位采样计算 0.0 到 1.0 范围内的归一化 RMS。
     */
    public static double rms(short[] samples) {
        if (samples.length == 0) {
            return 0.0;
        }
        double sumSquares = 0.0;
        for (short sample : samples) {
            double normalized = sample / 32768.0;
            sumSquares += normalized * normalized;
        }
        return Math.sqrt(sumSquares / samples.length);
    }

    /**
     * 将毫秒时长换算为指定音频格式下的字节数。
     */
    public static int bytesForMs(AudioFormatSpec spec, int ms) {
        return spec.bytesForMs(ms);
    }

    /**
     * 将毫秒时长换算为指定音频格式下的采样点数量。
     */
    public static int samplesForMs(AudioFormatSpec spec, int ms) {
        return spec.samplesForMs(ms);
    }

    /**
     * 拼接 PCM 块，并容忍可选来源里的 null 条目。
     */
    public static byte[] concat(byte[]... chunks) {
        int total = Arrays.stream(chunks).mapToInt(chunk -> chunk == null ? 0 : chunk.length).sum();
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            System.arraycopy(chunk, 0, out, offset, chunk.length);
            offset += chunk.length;
        }
        return out;
    }

    /**
     * 只保留最新字节，适用于有界音频窗口。
     */
    public static byte[] trimToLatest(byte[] pcm, int maxBytes) {
        if (pcm.length <= maxBytes) {
            return Arrays.copyOf(pcm, pcm.length);
        }
        return Arrays.copyOfRange(pcm, pcm.length - maxBytes, pcm.length);
    }
}
