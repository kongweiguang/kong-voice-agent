package io.github.kongweiguang.voice.agent.extension.transport.rtc.payload;

import io.github.kongweiguang.voice.agent.model.payload.AgentEventPayload;

import java.util.List;

/**
 * WebRTC 会话初始化完成后返回给前端的 payload。
 *
 * @param sessionId  当前控制面会话 id
 * @param iceServers 浏览器建链使用的 ICE server 列表
 * @author kongweiguang
 */
public record RtcSessionReadyPayload(String sessionId,
                                     List<RtcIceServerPayload> iceServers) implements AgentEventPayload {
    /**
     * 单个 ICE server 配置。
     *
     * @param urls ICE server URL 列表
     * @author kongweiguang
     */
    public record RtcIceServerPayload(List<String> urls) {
    }
}
