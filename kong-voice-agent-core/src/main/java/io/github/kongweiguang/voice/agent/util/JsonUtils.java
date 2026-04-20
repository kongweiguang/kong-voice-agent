package io.github.kongweiguang.voice.agent.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * WebSocket 控制消息和事件 JSON 共用的 Jackson 工具。
 *
 * @author kongweiguang
 */
public final class JsonUtils {
    /**
     * 项目统一 JSON mapper，保证时间字段按 ISO 字符串序列化。
     */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * 工具类不允许实例化。
     */
    private JsonUtils() {
    }

    /**
     * 将对象序列化为 WebSocket 文本消息使用的 JSON。
     */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize JSON", ex);
        }
    }

    /**
     * 将一条 WebSocket 文本消息解析为目标模型类型。
     */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid JSON", ex);
        }
    }
}
