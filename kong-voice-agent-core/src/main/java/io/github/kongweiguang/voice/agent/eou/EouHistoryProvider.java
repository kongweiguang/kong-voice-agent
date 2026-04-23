package io.github.kongweiguang.voice.agent.eou;

import java.util.List;

/**
 * EOU 实现按 sessionId 查询最近历史的公开扩展点。
 *
 * @author kongweiguang
 */
@FunctionalInterface
public interface EouHistoryProvider {
    /**
     * 返回指定会话最近的对话片段，调用方传入最大数量，避免实现暴露过多历史。
     */
    List<ConversationTurn> recentTurns(String sessionId, Integer maxTurns);

    /**
     * 不提供历史的默认实现，保持 core 不主动记录业务对话内容。
     */
    static EouHistoryProvider empty() {
        return (sessionId, maxTurns) -> List.of();
    }
}
