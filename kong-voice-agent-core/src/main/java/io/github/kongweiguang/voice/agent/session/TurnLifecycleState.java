package io.github.kongweiguang.voice.agent.session;

/**
 * turn 管理和下游播报共享的对话状态机。
 *
 * @author kongweiguang
 */
public enum TurnLifecycleState {
    /**
     * 会话空闲，尚未检测到当前用户 turn。
     */
    IDLE,

    /**
     * 刚检测到用户语音，准备进入正式说话态。
     */
    USER_PRE_SPEECH,

    /**
     * 用户正在说话，ASR partial 可持续更新。
     */
    USER_SPEAKING,

    /**
     * 检测到静音候选，等待端点策略确认是否结束本轮。
     */
    USER_ENDPOINTING,

    /**
     * 用户 turn 已提交，这是允许启动 LLM/TTS 的边界。
     */
    USER_TURN_COMMITTED,

    /**
     * Agent 正在生成或等待 LLM 回复。
     */
    AGENT_THINKING,

    /**
     * Agent 正在向客户端下发文本或 TTS 音频。
     */
    AGENT_SPEAKING,

    /**
     * 当前 turn 已被主动打断或插话打断。
     */
    INTERRUPTED
}
