package io.github.kongweiguang.voice.agent.playback;

import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.session.SessionTransportKind;
import org.springframework.stereotype.Component;

/**
 * 统一决定当前会话的 TTS 音频应该走哪条下行链路。
 *
 * @author kongweiguang
 */
@Component
public class SessionAudioPlaybackPolicy {
    /**
     * 判断当前会话是否仍需要通过 WebSocket `tts_audio_chunk` 下发音频。
     */
    public boolean shouldSendTtsChunkOverWebSocket(SessionState session) {
        return session.transportKind() != SessionTransportKind.WEBRTC || !session.audioEgressAdapter().available();
    }
}
