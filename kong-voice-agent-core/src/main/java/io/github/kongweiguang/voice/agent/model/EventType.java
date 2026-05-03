package io.github.kongweiguang.voice.agent.model;

/**
 * 通过 WebSocket JSON 协议暴露的下游事件名。
 *
 * @author kongweiguang
 */
public enum EventType {
    /**
     * 会话或轮次状态发生变化。
     */
    state_changed,

    /**
     * ASR 流式识别的中间文本。
     */
    asr_partial,

    /**
     * ASR 最终识别结果，文本输入时表示已提交的用户文本。
     */
    asr_final,

    /**
     * Agent 已进入思考阶段，准备调用或正在等待 LLM。
     */
    agent_thinking,

    /**
     * Agent 回复文本的流式片段。
     */
    agent_text_chunk,

    /**
     * TTS 生成的音频片段。
     */
    tts_audio_chunk,

    /**
     * 当前 turn 的阶段耗时指标。
     */
    turn_metrics,

    /**
     * WebRTC trickle ICE candidate。
     */
    rtc_ice_candidate,

    /**
     * WebRTC signaling 会话已就绪，前端可继续创建 PeerConnection。
     */
    rtc_session_ready,

    /**
     * 服务端返回给浏览器的 WebRTC SDP answer。
     */
    rtc_answer,

    /**
     * WebRTC 运行态状态变化，便于前端观察建链、断流和恢复过程。
     */
    rtc_state_changed,

    /**
     * 当前播报需要停止，通常由用户打断触发。
     */
    playback_stop,

    /**
     * 当前 turn 已被打断并失效。
     */
    turn_interrupted,

    /**
     * 协议解析、处理流程或业务执行失败。
     */
    error,

    /**
     * ping 消息对应的心跳响应。
     */
    pong
}
