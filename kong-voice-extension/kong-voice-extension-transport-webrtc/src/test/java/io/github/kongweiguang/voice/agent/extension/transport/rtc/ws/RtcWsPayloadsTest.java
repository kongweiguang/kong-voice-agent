package io.github.kongweiguang.voice.agent.extension.transport.rtc.ws;

import io.github.kongweiguang.voice.agent.util.JsonUtils;
import io.github.kongweiguang.voice.agent.ws.WsMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖 WebRTC signaling payload 的边界校验，避免不同 handler 各自漂移。
 *
 * @author kongweiguang
 */
@Tag("protocol")
@DisplayName("WebRTC signaling payload 解析")
class RtcWsPayloadsTest {
    @Test
    @DisplayName("rtc_close 允许省略 sessionId 并回退到当前控制面 session")
    void closeFallsBackToCurrentSessionId() throws Exception {
        RtcWsPayloads.RtcCloseRequest request = RtcWsPayloads.close(
                new WsMessage("rtc_close", JsonUtils.MAPPER.readTree("{}")),
                "sess_current"
        );

        assertThat(request.sessionId()).isEqualTo("sess_current");
    }

    @Test
    @DisplayName("rtc_offer 的 sessionId 必须与当前控制面 session 一致")
    void offerRequiresMatchingControlSession() throws Exception {
        WsMessage message = new WsMessage("rtc_offer", JsonUtils.MAPPER.readTree("""
                {"sessionId":"sess_other","type":"offer","sdp":"offer-sdp"}
                """));

        assertThatThrownBy(() -> RtcWsPayloads.offer(message, "sess_current"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("payload.sessionId must match current control session");
    }

    @Test
    @DisplayName("rtc_ice_candidate 的 sdpMLineIndex 必须是整数")
    void iceCandidateRequiresIntegerMLineIndex() throws Exception {
        WsMessage message = new WsMessage("rtc_ice_candidate", JsonUtils.MAPPER.readTree("""
                {"sessionId":"sess_current","sdpMid":"0","sdpMLineIndex":"zero","candidate":"candidate"}
                """));

        assertThatThrownBy(() -> RtcWsPayloads.iceCandidate(message, "sess_current"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("payload.sdpMLineIndex must be an integer");
    }
}
