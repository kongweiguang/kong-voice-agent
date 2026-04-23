package io.github.kongweiguang.voice.agent.eou;

/**
 * EOU 判断可见的最近对话片段。
 *
 * @param role 对话角色，当前约定为 user 或 assistant
 * @param text 该角色在本轮中的文本内容
 * @author kongweiguang
 */
public record ConversationTurn(String role, String text) {
    /**
     * 归一化角色和文本，避免 prompt 构造阶段处理 null。
     */
    public ConversationTurn {
        role = role == null || role.isBlank() ? "user" : role.trim();
        text = text == null ? "" : text.trim();
    }
}
