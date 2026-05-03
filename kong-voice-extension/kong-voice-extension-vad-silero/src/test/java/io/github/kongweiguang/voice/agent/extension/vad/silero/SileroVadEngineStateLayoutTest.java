package io.github.kongweiguang.voice.agent.extension.vad.silero;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Silero 循环状态张量在不同模型布局之间的兼容行为。
 *
 * @author kongweiguang
 */
@Tag("vad")
@Tag("protocol")
@DisplayName("Silero 状态张量布局兼容")
class SileroVadEngineStateLayoutTest {

    private final SileroVadEngine engine = new SileroVadEngine(new VadConfig(null, null, true), new DefaultResourceLoader());

    @Test
    @DisplayName("输入 shape 为 [2,1,64] 时按模型期望输出状态张量")
    void reshapesStateTo201WhenModelExpects201() {
        float[][] state = seedState();

        float[][][] reshaped = engine.reshapeStateForInput(new long[]{2, 1, 64}, state);

        assertThat(reshaped.length).isEqualTo(2);
        assertThat(reshaped[0].length).isEqualTo(1);
        assertThat(reshaped[0][0].length).isEqualTo(64);
        assertThat(reshaped[0][0][0]).isEqualTo(1.0f);
        assertThat(reshaped[1][0][0]).isEqualTo(101.0f);
    }

    @Test
    @DisplayName("模型返回 [1,2,64] 布局时可归一为 [2,64]")
    void normalizes120StateLayout() {
        float[][][] state120 = new float[1][2][64];
        state120[0][0][0] = 11.0f;
        state120[0][1][0] = 22.0f;

        float[][] normalized = engine.normalizeStateLayout(state120);

        assertThat(normalized).hasDimensions(2, 64);
        assertThat(normalized[0][0]).isEqualTo(11.0f);
        assertThat(normalized[1][0]).isEqualTo(22.0f);
    }

    @Test
    @DisplayName("模型返回 [2,1,64] 布局时可归一为 [2,64]")
    void normalizes201StateLayout() {
        float[][][] state201 = new float[2][1][64];
        state201[0][0][0] = 33.0f;
        state201[1][0][0] = 44.0f;

        float[][] normalized = engine.normalizeStateLayout(state201);

        assertThat(normalized).hasDimensions(2, 64);
        assertThat(normalized[0][0]).isEqualTo(33.0f);
        assertThat(normalized[1][0]).isEqualTo(44.0f);
    }

    private float[][] seedState() {
        float[][] state = new float[2][64];
        for (int i = 0; i < 64; i++) {
            state[0][i] = i + 1;
            state[1][i] = i + 101;
        }
        return state;
    }
}
