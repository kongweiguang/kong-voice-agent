package io.github.kongweiguang.voice.agent.eou;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 EOU 配置默认值和边界归一化。
 *
 * @author kongweiguang
 */
@Tag("eou")
@DisplayName("EOU 配置")
class EouConfigTest {
    @Test
    @DisplayName("缺省值可支持开源用户直接启动")
    void normalizesDefaults() {
        EouConfig config = new EouConfig(null, null, null, null, true, 0, 0, 0, 0, null);

        assertThat(config.enabled()).isTrue();
        assertThat(config.provider()).isEqualTo("livekit-multilingual");
        assertThat(config.modelPath()).isEqualTo("file:models/livekit-turn-detector/model_quantized.onnx");
        assertThat(config.tokenizerPath()).isEqualTo("file:models/livekit-turn-detector/tokenizer.json");
        assertThat(config.defaultThreshold()).isEqualTo(0.5);
        assertThat(config.minSilenceMs()).isEqualTo(500);
        assertThat(config.maxSilenceMs()).isEqualTo(1600);
        assertThat(config.language()).isEqualTo("zh");
    }
}
