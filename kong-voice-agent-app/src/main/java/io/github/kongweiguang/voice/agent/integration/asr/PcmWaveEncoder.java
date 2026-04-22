package io.github.kongweiguang.voice.agent.integration.asr;

import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 将 WebSocket 收到的 s16le PCM 包装成标准 WAV 文件字节，供 Whisper HTTP 服务识别。
 *
 * @author kongweiguang
 */
public final class PcmWaveEncoder {
    /**
     * WAV RIFF 头部固定长度。
     */
    private static final int WAV_HEADER_BYTES = 44;

    /**
     * PCM 音频格式编码，1 表示线性 PCM。
     */
    private static final short PCM_FORMAT = 1;

    /**
     * 每个 PCM 采样点的位深。
     */
    private static final short BITS_PER_SAMPLE = 16;

    /**
     * 工具类不允许实例化。
     */
    private PcmWaveEncoder() {
    }

    /**
     * 将原始 s16le PCM 加上 WAV 头，保留原采样率和声道配置。
     */
    public static byte[] encode(byte[] pcm, AudioFormatSpec format) {
        byte[] safePcm = pcm == null ? new byte[0] : pcm;
        ByteArrayOutputStream out = new ByteArrayOutputStream(WAV_HEADER_BYTES + safePcm.length);
        int byteRate = format.sampleRate() * format.channels() * format.bytesPerSample();
        short blockAlign = (short) (format.channels() * format.bytesPerSample());
        writeAscii(out, "RIFF");
        writeIntLe(out, 36 + safePcm.length);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeIntLe(out, 16);
        writeShortLe(out, PCM_FORMAT);
        writeShortLe(out, (short) format.channels());
        writeIntLe(out, format.sampleRate());
        writeIntLe(out, byteRate);
        writeShortLe(out, blockAlign);
        writeShortLe(out, BITS_PER_SAMPLE);
        writeAscii(out, "data");
        writeIntLe(out, safePcm.length);
        out.writeBytes(safePcm);
        return out.toByteArray();
    }

    /**
     * 按 ASCII 写入 WAV chunk 标识。
     */
    private static void writeAscii(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * 按 little-endian 写入 32 位整数。
     */
    private static void writeIntLe(ByteArrayOutputStream out, int value) {
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    /**
     * 按 little-endian 写入 16 位整数。
     */
    private static void writeShortLe(ByteArrayOutputStream out, short value) {
        out.writeBytes(ByteBuffer.allocate(Short.BYTES).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array());
    }
}
