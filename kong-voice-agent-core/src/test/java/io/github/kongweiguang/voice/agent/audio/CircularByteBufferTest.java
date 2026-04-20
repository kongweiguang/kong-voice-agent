package io.github.kongweiguang.voice.agent.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证滚动音频缓冲区会保留最新 PCM 窗口。
 *
 * @author kongweiguang
 */
@Tag("audio")
@DisplayName("音频缓冲区")
class CircularByteBufferTest {
    /**
     * 确保缓冲区超容量时会丢弃旧字节。
     */
    @Test
    @DisplayName("容量溢出时只保留最新字节")
    void keepsLatestBytesWhenCapacityIsExceeded() {
        CircularByteBuffer buffer = new CircularByteBuffer(5);

        buffer.write(new byte[]{1, 2, 3});
        buffer.write(new byte[]{4, 5, 6});

        assertThat(buffer.snapshot()).containsExactly(2, 3, 4, 5, 6);
        assertThat(buffer.readLatest(3)).containsExactly(4, 5, 6);
    }
}
