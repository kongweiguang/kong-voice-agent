package io.github.kongweiguang.voice.agent.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证固定 16kHz 单声道 s16le 约定下的 PCM 工具行为。
 *
 * @author kongweiguang
 */
@Tag("audio")
@DisplayName("PCM 工具")
class PcmUtilsTest {
    /**
     * 保护字节序和 RMS 计算，这两者都会被模拟 VAD 使用。
     */
    @Test
    @DisplayName("转换 little-endian PCM 并计算 RMS")
    void convertsLittleEndianPcmAndCalculatesRms() {
        byte[] pcm = new byte[]{0x00, 0x00, 0x00, 0x40, 0x00, (byte) 0xC0};

        short[] samples = PcmUtils.littleEndianBytesToShorts(pcm);

        assertThat(samples).containsExactly((short) 0, (short) 16384, (short) -16384);
        assertThat(PcmUtils.rms(samples)).isBetween(0.40, 0.41);
    }

    /**
     * 保护缓冲区使用的时长转换和字节窗口工具。
     */
    @Test
    @DisplayName("换算时长并处理 PCM 字节窗口")
    void convertsDurationsAndConcatenatesPcm() {
        AudioFormatSpec spec = AudioFormatSpec.DEFAULT;

        assertThat(PcmUtils.bytesForMs(spec, 20)).isEqualTo(640);
        assertThat(PcmUtils.samplesForMs(spec, 20)).isEqualTo(320);
        assertThat(PcmUtils.concat(new byte[]{1}, new byte[]{2, 3})).containsExactly(1, 2, 3);
        assertThat(PcmUtils.trimToLatest(new byte[]{1, 2, 3, 4}, 2)).containsExactly(3, 4);
    }
}
