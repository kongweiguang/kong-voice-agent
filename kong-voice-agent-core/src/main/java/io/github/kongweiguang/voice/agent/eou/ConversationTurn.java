package io.github.kongweiguang.voice.agent.eou;

/**
 * EOU 判断可见的最近对话片段。
 *
 * @param role 对话角色，当前约定为 user 或 assistant
 * @param text 该角色在本轮中的文本内容
 * @author kongweiguang
 */
public record ConversationTurn(
        /**
         * 对话角色，当前约定为 user 或 assistant。
         */
        String role,

        /**
         * 对话文本，创建时会归一化为空字符串或去除首尾空白。
         */
        String text) {
    /**
     * 归一化角色和文本，避免 prompt 构造阶段处理 null。
     */
    public ConversationTurn {
        role = role == null || role.isBlank() ? "user" : role.trim();
        text = text == null ? "" : text.trim();
    }
}
