package io.github.kongweiguang.voice.agent.ws;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 客户端 JSON 文本消息外壳；音频本身通过二进制帧发送。
 *
 * <p>payload 保留为 JsonNode，避免入站协议在核心边界退化成松散 Map。
 * 新增控制消息时应在这里补充明确的读取方法，而不是在 handler 中散落类型判断。</p>
 *
 * @param type    客户端上行 JSON 消息类型，保留字符串以支持业务方扩展自定义 type
 * @param payload 客户端上行 JSON 消息载荷，具体字段由 type 决定
 * @author kongweiguang
 */
public record WsMessage(String type, JsonNode payload) {
    /**
     * 读取文本输入的用户内容。这里集中约束 payload.text，后续扩展文本参数时
     * 可以继续在协议模型层做校验，避免 WebSocket handler 变成字段解析集合。
     */
    public String textPayload() {
        JsonNode textNode = payload == null ? null : payload.get("text");
        if (textNode == null || !textNode.isTextual()) {
            throw new IllegalArgumentException("payload.text is required");
        }
        // 保留原始文本内容，是否 trim 和判空由流水线文本入口统一处理。
        return textNode.asText();
    }
}
