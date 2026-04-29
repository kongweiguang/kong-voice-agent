package io.github.kongweiguang.voice.agent.playback;

import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.media.AudioEgressAdapter;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.session.SessionTransportKind;
import io.github.kongweiguang.voice.agent.support.NoopStreamingAsrAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证不同传输层下 TTS 音频是否仍需通过 WebSocket 下发。
 *
 * @author kongweiguang
 */
@Tag("audio")
@DisplayName("Session 音频下行策略")
class SessionAudioPlaybackPolicyTest {
    private final SessionAudioPlaybackPolicy policy = new SessionAudioPlaybackPolicy();

    @Test
    @DisplayName("WS PCM 模式仍通过 WebSocket 下发 TTS chunk")
    void sendsTtsChunkOverWebSocketForWsPcm() {
        SessionState sessionState = sessionState();
        sessionState.transportKind(SessionTransportKind.WS_PCM);

        assertThat(policy.shouldSendTtsChunkOverWebSocket(sessionState)).isTrue();
    }

    @Test
    @DisplayName("WebRTC 模式且下行适配器可用时不再重复走 WebSocket")
    void skipsWebSocketTtsChunkWhenRtcEgressIsAvailable() {
        SessionState sessionState = sessionState();
        sessionState.transportKind(SessionTransportKind.WEBRTC);
        sessionState.audioEgressAdapter(new AvailableAudioEgressAdapter());

        assertThat(policy.shouldSendTtsChunkOverWebSocket(sessionState)).isFalse();
    }

    @Test
    @DisplayName("WebRTC 模式但下行适配器不可用时回退到 WebSocket")
    void fallsBackToWebSocketWhenRtcEgressIsUnavailable() {
        SessionState sessionState = sessionState();
        sessionState.transportKind(SessionTransportKind.WEBRTC);
        sessionState.audioEgressAdapter(AudioEgressAdapter.noop());

        assertThat(policy.shouldSendTtsChunkOverWebSocket(sessionState)).isTrue();
    }

    private SessionState sessionState() {
        return new SessionState("sess_transport", AudioFormatSpec.DEFAULT, (ignored, format) -> new NoopStreamingAsrAdapter(), eouConfig());
    }

    private EouConfig eouConfig() {
        return new EouConfig(true, null, null, null, true, 0.5, 500, 1600, 300, "zh");
    }

    /**
     * 用于表达“RTC 下行链路已可用”的最小适配器。
     */
    private static final class AvailableAudioEgressAdapter implements AudioEgressAdapter {
        @Override
        public boolean available() {
            return true;
        }
    }
}
