package io.github.kongweiguang.voice.agent.model.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * WebRTC 运行态变化 payload。
 *
 * @param state  当前 RTC 阶段或状态，例如 connected、failed、media_flowing
 * @param source 状态来源，例如 session、ice、peer_connection、media
 * @param detail 辅助排查的附加细节，允许为空
 * @author kongweiguang
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RtcStateChangedPayload(String state,
                                     String source,
                                     String detail) implements AgentEventPayload {
}
