package io.github.kongweiguang.voice.agent.ws;

/**
 * 支持的客户端到服务端 JSON 文本消息类型。
 *
 * @author kongweiguang
 */
public enum WsMessageType {
    /**
     * 客户端心跳探测，服务端返回 pong。
     */
    ping,

    /**
     * 客户端主动请求打断当前 Agent 播报。
     */
    interrupt,

    /**
     * 客户端声明当前音频流结束，服务端尝试提交当前 turn。
     */
    audio_end,

    /**
     * 客户端直接提交完整用户文本，跳过 VAD/ASR partial 阶段。
     */
    text
}
