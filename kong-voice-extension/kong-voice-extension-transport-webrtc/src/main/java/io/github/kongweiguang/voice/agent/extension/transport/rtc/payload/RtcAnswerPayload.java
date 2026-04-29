package io.github.kongweiguang.voice.agent.extension.transport.rtc.payload;

import io.github.kongweiguang.voice.agent.model.payload.AgentEventPayload;

/**
 * WebRTC answer 事件载荷。
 *
 * @param type SDP 类型，当前通常为 `ANSWER`
 * @param sdp  服务端返回的 SDP 文本
 * @author kongweiguang
 */
public record RtcAnswerPayload(String type,
                               String sdp) implements AgentEventPayload {
}
