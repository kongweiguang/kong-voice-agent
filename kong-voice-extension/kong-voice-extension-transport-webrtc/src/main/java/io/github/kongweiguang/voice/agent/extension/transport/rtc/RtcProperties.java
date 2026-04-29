package io.github.kongweiguang.voice.agent.extension.transport.rtc;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * WebRTC 接入配置。
 *
 * @param enabled            是否启用 WebRTC 接入层；关闭时仅保留原有 WebSocket + PCM 路径
 * @param iceServers         浏览器建链时使用的 ICE server 列表
 * @param signalingTimeoutMs SDP offer / answer 等待超时时间，单位毫秒
 * @author kongweiguang
 */
@ConfigurationProperties(prefix = "kong-voice-agent.rtc")
public record RtcProperties(Boolean enabled,
                            List<String> iceServers,
                            Integer signalingTimeoutMs) {
    /**
     * 归一化默认值，便于本地联调用默认配置直接验证。
     */
    public RtcProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (iceServers == null || iceServers.isEmpty()) {
            iceServers = List.of("stun:stun.l.google.com:19302");
        }
        if (signalingTimeoutMs == null || signalingTimeoutMs <= 0) {
            signalingTimeoutMs = 15_000;
        }
    }
}
