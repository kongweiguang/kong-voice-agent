package io.github.kongweiguang.voice.agent.extension.asr.qwen;

import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Qwen ASR Realtime 配置与手动提交模式约定一致。
 *
 * @author kongweiguang
 */
@Tag("audio")
@Tag("protocol")
@DisplayName("DashScope Qwen ASR Realtime 会话工厂")
class DashScopeQwenRealtimeSessionFactoryTest {
    @Test
    @DisplayName("关闭 turn detection 时不下发服务端断句配置")
    void shouldOmitTurnDetectionConfigWhenDisabled() {
        DashScopeQwenRealtimeSessionFactory factory = new DashScopeQwenRealtimeSessionFactory();

        OmniRealtimeConfig config = factory.realtimeConfig(properties(false), AudioFormatSpec.DEFAULT);

        assertThat(config.getConfig().toString())
                .contains("\"turn_detection\":null")
                .doesNotContain("server_vad");
    }

    @Test
    @DisplayName("开启 turn detection 时保留下发服务端断句配置")
    void shouldIncludeTurnDetectionConfigWhenEnabled() {
        DashScopeQwenRealtimeSessionFactory factory = new DashScopeQwenRealtimeSessionFactory();

        OmniRealtimeConfig config = factory.realtimeConfig(properties(true), AudioFormatSpec.DEFAULT);

        assertThat(config.isEnableTurnDetection()).isTrue();
        assertThat(config.getConfig().toString())
                .contains("turn_detection")
                .contains("server_vad");
    }

    /**
     * 构造测试配置。
     */
    private QwenAsrProperties properties(boolean enableTurnDetection) {
        return new QwenAsrProperties(
                true,
                "test-key",
                "wss://example.test/realtime",
                "qwen3-asr-flash-realtime",
                "zh",
                "pcm",
                enableTurnDetection,
                "server_vad",
                0.0f,
                400,
                30000
        );
    }
}
