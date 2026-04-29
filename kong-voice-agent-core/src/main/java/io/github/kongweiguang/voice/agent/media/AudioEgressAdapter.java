package io.github.kongweiguang.voice.agent.media;

import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;

/**
 * 统一抽象不同媒体通道的音频下行能力。
 * 当前 WebSocket 控制面仍保留 `tts_audio_chunk` 事件，而 WebRTC 等媒体通道
 * 可以通过该扩展点把 TTS 音频直接写回各自的实时播放链路。
 *
 * @author kongweiguang
 */
public interface AudioEgressAdapter {
    /**
     * 返回当前下行适配器是否真正可用。
     */
    default boolean available() {
        return false;
    }

    /**
     * 收到新的 TTS 音频块后回写到对应媒体通道。
     *
     * @param session 当前会话状态
     * @param chunk   本次 TTS 音频块
     */
    default void onTtsChunk(SessionState session, TtsChunk chunk) {
    }

    /**
     * 当前播放被打断或停止时清理媒体通道中的待播音频。
     *
     * @param session 当前会话状态
     * @param turnId  被停止的 turnId
     * @param reason  停止原因
     */
    default void onPlaybackStop(SessionState session, String turnId, String reason) {
    }

    /**
     * 会话结束时释放适配器占用的资源。
     */
    default void close() {
    }

    /**
     * 返回一个无副作用的空实现，便于会话在未挂载真实媒体通道时保持统一调用方式。
     */
    static AudioEgressAdapter noop() {
        return NoopAudioEgressAdapter.INSTANCE;
    }
}
