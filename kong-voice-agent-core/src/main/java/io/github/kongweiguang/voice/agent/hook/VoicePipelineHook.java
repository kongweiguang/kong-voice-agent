package io.github.kongweiguang.voice.agent.hook;

import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.llm.LlmChunk;
import io.github.kongweiguang.voice.agent.llm.LlmRequest;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;
import org.springframework.web.socket.WebSocketSession;

/**
 * 语音流水线扩展钩子，采用 Observer/Hook 模式。业务模块可以注册一个或多个 Bean，
 * 在不改公共流水线的情况下记录审计、埋点、鉴权或改造上下文。
 *
 * @author kongweiguang
 */
public interface VoicePipelineHook {
    /**
     * 接收到客户端二进制 PCM 音频块后触发，用于审计、埋点或记录原始音频入口信息。
     *
     * @param session 当前 WebSocket 连接绑定的会话状态
     * @param ws 当前 WebSocket 连接
     * @param pcm 本次收到的 PCM s16le 音频字节
     */
    default void onAudioReceived(SessionState session, WebSocketSession ws, byte[] pcm) {
    }

    /**
     * 接收到客户端文本输入后触发，文本输入会作为已提交用户 turn 进入后续 LLM/TTS 流程。
     *
     * @param session 当前 WebSocket 连接绑定的会话状态
     * @param ws 当前 WebSocket 连接
     * @param text 客户端提交的用户文本
     */
    default void onTextReceived(SessionState session, WebSocketSession ws, String text) {
    }

    /**
     * 用户 turn 提交后触发，此时 final ASR 或文本输入已经确定，可以记录本轮用户输入边界。
     *
     * @param session 当前 WebSocket 连接绑定的会话状态
     * @param ws 当前 WebSocket 连接
     * @param finalAsr 本轮最终识别结果或由文本输入构造的最终结果
     * @param source turn 来源，例如音频 ASR 或直接文本输入
     */
    default void onTurnCommitted(SessionState session, WebSocketSession ws, AsrUpdate finalAsr, String source) {
    }

    /**
     * 调用 LLM 前触发，用于观察或补充请求上下文；实现方需要遵守 turn commit 后才调用 LLM 的边界。
     *
     * @param session 当前 WebSocket 连接绑定的会话状态
     * @param ws 当前 WebSocket 连接
     * @param request 即将提交给 LLM 编排器的请求
     */
    default void beforeLlm(SessionState session, WebSocketSession ws, LlmRequest request) {
    }

    /**
     * 收到 LLM 流式文本块后触发，用于记录模型输出、埋点或观察分块响应。
     *
     * @param session 当前 WebSocket 连接绑定的会话状态
     * @param ws 当前 WebSocket 连接
     * @param chunk LLM 返回的单个流式文本块
     */
    default void onLlmChunk(SessionState session, WebSocketSession ws, LlmChunk chunk) {
    }

    /**
     * 收到 TTS 音频块后触发，用于记录播报音频、文本切片或音频下发前的观察信息。
     *
     * @param session 当前 WebSocket 连接绑定的会话状态
     * @param ws 当前 WebSocket 连接
     * @param chunk TTS 返回的单个音频块
     */
    default void onTtsChunk(SessionState session, WebSocketSession ws, TtsChunk chunk) {
    }

    /**
     * 当前播报或处理流程被打断后触发，用于记录打断原因和新的 turn 边界。
     *
     * @param session 当前 WebSocket 连接绑定的会话状态
     * @param ws 当前 WebSocket 连接
     * @param newTurnId 打断后分配的新 turnId
     * @param reason 打断原因，例如客户端主动打断或用户重新开口
     */
    default void onInterrupted(SessionState session, WebSocketSession ws, long newTurnId, String reason) {
    }
}
