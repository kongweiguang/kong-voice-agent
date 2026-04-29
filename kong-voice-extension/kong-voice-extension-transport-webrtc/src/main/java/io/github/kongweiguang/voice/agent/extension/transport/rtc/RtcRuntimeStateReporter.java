package io.github.kongweiguang.voice.agent.extension.transport.rtc;

import dev.onvoid.webrtc.RTCIceConnectionState;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import io.github.kongweiguang.voice.agent.model.AgentEvent;
import io.github.kongweiguang.voice.agent.model.EventType;
import io.github.kongweiguang.voice.agent.model.payload.RtcStateChangedPayload;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.session.SessionState;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 将 WebRTC 内部生命周期状态转换成统一的协议事件，便于前端联调和后续观测。
 *
 * @author kongweiguang
 */
final class RtcRuntimeStateReporter {
    /**
     * 当前业务会话。
     */
    private final SessionState sessionState;

    /**
     * 控制面下行发送器。
     */
    private final PlaybackDispatcher playbackDispatcher;

    /**
     * 避免同一个状态连续重复下发，降低联调噪音。
     */
    private final AtomicReference<String> lastFingerprint = new AtomicReference<>();

    /**
     * 创建当前 RTC 会话的状态上报器。
     */
    RtcRuntimeStateReporter(SessionState sessionState, PlaybackDispatcher playbackDispatcher) {
        this.sessionState = sessionState;
        this.playbackDispatcher = playbackDispatcher;
    }

    /**
     * 会话已创建，等待浏览器继续完成 offer / answer。
     */
    void sessionOpened() {
        emit("session_opened", "session", "rtc_start");
    }

    /**
     * 服务端已经绑定浏览器传来的远端音轨。
     */
    void trackBound() {
        emit("track_bound", "media", "remote_track_attached");
    }

    /**
     * 第一帧 RTC 音频已经真正进入后端流水线。
     */
    void mediaFlowing() {
        emit("media_flowing", "media", "first_audio_frame");
    }

    /**
     * PeerConnection 生命周期状态变化。
     */
    void peerConnectionState(RTCPeerConnectionState state) {
        if (state == null) {
            return;
        }
        switch (state) {
            case CONNECTED -> emit("connected", "peer_connection", "CONNECTED");
            case DISCONNECTED -> emit("disconnected", "peer_connection", "DISCONNECTED");
            case FAILED -> emit("failed", "peer_connection", "FAILED");
            case CLOSED -> emit("closed", "peer_connection", "CLOSED");
            default -> {
                // 其余状态暂不作为协议事件暴露，避免前端消费过早绑定到浏览器实现细节。
            }
        }
    }

    /**
     * ICE 生命周期状态变化。
     */
    void iceConnectionState(RTCIceConnectionState state) {
        if (state == null) {
            return;
        }
        switch (state) {
            case CONNECTED, COMPLETED -> emit("connected", "ice", state.name());
            case DISCONNECTED -> emit("disconnected", "ice", "DISCONNECTED");
            case FAILED -> emit("failed", "ice", "FAILED");
            case CLOSED -> emit("closed", "ice", "CLOSED");
            default -> {
                // 其余中间状态继续留给浏览器本地日志观察。
            }
        }
    }

    /**
     * 当前 RTC 会话被显式关闭或因异常回收。
     */
    void closed(String detail) {
        emit("closed", "session", detail);
    }

    /**
     * 发出统一的 rtc_state_changed 事件。
     */
    private void emit(String state, String source, String detail) {
        String normalizedDetail = detail == null || detail.isBlank() ? null : detail.trim();
        String fingerprint = state + "|" + source + "|" + (normalizedDetail == null ? "" : normalizedDetail);
        String previous = lastFingerprint.getAndSet(fingerprint);
        if (fingerprint.equals(previous)) {
            return;
        }
        playbackDispatcher.send(
                sessionState,
                AgentEvent.of(
                        EventType.rtc_state_changed,
                        sessionState.sessionId(),
                        sessionState.currentTurnId(),
                        new RtcStateChangedPayload(state, source, normalizedDetail)
                )
        );
    }
}
