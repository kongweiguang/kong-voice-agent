package io.github.kongweiguang.voice.agent.session;

/**
 * 当前会话实际承载音频输入输出的传输类型。
 *
 * @author kongweiguang
 */
public enum SessionTransportKind {
    /**
     * 默认传输模式：控制面和音频面都走 WebSocket。
     */
    WS_PCM,

    /**
     * WebRTC 模式：控制面继续走 WebSocket，音频输入输出改走 RTC 媒体链路。
     */
    WEBRTC
}
