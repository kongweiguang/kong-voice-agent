package io.github.kongweiguang.voice.agent.model;

import io.github.kongweiguang.voice.agent.model.payload.AgentEventPayload;
import io.github.kongweiguang.voice.agent.model.payload.EmptyPayload;

import java.time.Instant;

/**
 * 后端发送给客户端的统一 WebSocket 事件外壳。
 *
 * @param type      事件类型，对应 WebSocket 下行协议中的 type 字段
 * @param sessionId 当前 WebSocket 连接绑定的会话标识
 * @param turnId    当前事件所属的对话轮次标识，用于客户端丢弃过期消息
 * @param timestamp 服务端创建事件的时间戳
 * @param payload   事件专属载荷，空载荷事件使用 {@link EmptyPayload}
 * @author kongweiguang
 */
public record AgentEvent(EventType type, String sessionId, String turnId, Instant timestamp, AgentEventPayload payload) {
    /**
     * 创建带当前时间戳的事件，并在 payload 为空时使用空实体兜底。
     */
    public static AgentEvent of(EventType type, String sessionId, String turnId, AgentEventPayload payload) {
        return new AgentEvent(type, sessionId, turnId, Instant.now(), payload == null ? EmptyPayload.INSTANCE : payload);
    }
}
