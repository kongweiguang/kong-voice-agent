package io.github.kongweiguang.voice.agent.asr;

import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;

/**
 * 为每个会话创建独立 ASR 实例的工厂，采用 Factory 模式。业务模块可以覆盖该 Bean，
 * 为不同业务接入自己的流式 ASR，并避免多个会话共享 ASR 内部状态。
 *
 * @author kongweiguang
 */
public interface StreamingAsrAdapterFactory {
    /**
     * 为指定 WebSocket 会话创建独立 ASR 适配器实例。
     */
    StreamingAsrAdapter create(String sessionId, AudioFormatSpec format);
}
