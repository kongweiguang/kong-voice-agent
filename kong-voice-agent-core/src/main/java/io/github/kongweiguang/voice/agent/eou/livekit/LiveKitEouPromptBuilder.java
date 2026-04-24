package io.github.kongweiguang.voice.agent.eou.livekit;

import io.github.kongweiguang.voice.agent.eou.ConversationTurn;
import io.github.kongweiguang.voice.agent.eou.EouContext;
import io.github.kongweiguang.voice.agent.eou.EouHistoryProvider;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * LiveKit MultilingualModel 风格的 Qwen chat template prompt 构造器。
 *
 * @author kongweiguang
 */
@RequiredArgsConstructor
public class LiveKitEouPromptBuilder {
    /**
     * 参与 EOU 判断的最近对话 turn 数量。
     */
    public static final int MAX_HISTORY_TURNS = 6;

    /**
     * 历史记录读取扩展点，core 自身不记录业务对话历史。
     */
    private final EouHistoryProvider historyProvider;

    /**
     * 构建供 tokenizer 编码的 prompt。
     */
    public String build(EouContext context) {
        List<ConversationTurn> turns = new ArrayList<>(historyProvider.recentTurns(context.sessionId(), MAX_HISTORY_TURNS - 1));
        turns.add(new ConversationTurn("user", context.currentTranscript()));
        int from = Math.max(0, turns.size() - MAX_HISTORY_TURNS);
        List<ConversationTurn> recent = turns.subList(from, turns.size());
        StringBuilder prompt = new StringBuilder();
        for (int i = 0; i < recent.size(); i++) {
            ConversationTurn turn = recent.get(i);
            boolean last = i == recent.size() - 1;
            prompt.append("<|im_start|>").append(turn.role()).append('\n')
                    .append(turn.text()).append('\n');
            // 最后一个 user turn 故意不补 im_end，让模型预测下一 token 是否应结束。
            if (!last) {
                prompt.append("<|im_end|>\n");
            }
        }
        return prompt.toString();
    }
}
