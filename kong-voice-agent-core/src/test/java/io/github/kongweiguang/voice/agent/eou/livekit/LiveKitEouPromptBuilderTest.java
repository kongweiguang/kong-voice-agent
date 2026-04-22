package io.github.kongweiguang.voice.agent.eou.livekit;

import io.github.kongweiguang.voice.agent.eou.ConversationTurn;
import io.github.kongweiguang.voice.agent.eou.EouContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 LiveKit 风格 prompt 构造边界。
 *
 * @author kongweiguang
 */
@Tag("eou")
@DisplayName("LiveKit EOU prompt")
class LiveKitEouPromptBuilderTest {
    @Test
    @DisplayName("最后一个用户输入不追加 im_end")
    void leavesFinalUserTurnOpen() {
        LiveKitEouPromptBuilder builder = new LiveKitEouPromptBuilder(
                (sessionId, maxTurns) -> List.of(new ConversationTurn("assistant", "你好"))
        );

        String prompt = builder.build(new EouContext("s1", "turn-1", "我想查一下明天的天气", "zh", 600));

        assertThat(prompt).contains("<|im_start|>assistant\n你好\n<|im_end|>");
        assertThat(prompt).endsWith("<|im_start|>user\n我想查一下明天的天气\n");
    }

    @Test
    @DisplayName("最多保留最近 6 个 turn")
    void keepsRecentSixTurns() {
        List<ConversationTurn> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            history.add(new ConversationTurn(i % 2 == 0 ? "user" : "assistant", "turn-" + i));
        }
        LiveKitEouPromptBuilder builder = new LiveKitEouPromptBuilder((sessionId, maxTurns) -> history);

        String prompt = builder.build(new EouContext("s1", "turn-1", "current", "zh", 600));

        assertThat(prompt).doesNotContain("turn-0", "turn-1", "turn-2");
        assertThat(prompt).contains("turn-3", "turn-7", "current");
    }
}
