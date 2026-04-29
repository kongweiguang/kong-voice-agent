package io.github.kongweiguang.voice.agent.model.payload;

/**
 * WebRTC ICE candidate 下行载荷。
 *
 * @param sdpMid        candidate 对应的媒体段标识
 * @param sdpMLineIndex candidate 对应的 m-line 下标
 * @param candidate     ICE candidate SDP 字符串
 * @author kongweiguang
 */
public record RtcIceCandidatePayload(String sdpMid,
                                     Integer sdpMLineIndex,
                                     String candidate) implements AgentEventPayload {
}
