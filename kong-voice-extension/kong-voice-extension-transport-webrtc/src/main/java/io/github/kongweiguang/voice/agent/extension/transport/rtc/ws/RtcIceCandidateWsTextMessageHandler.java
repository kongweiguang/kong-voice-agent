package io.github.kongweiguang.voice.agent.extension.transport.rtc.ws;

import dev.onvoid.webrtc.RTCIceCandidate;
import io.github.kongweiguang.voice.agent.extension.transport.rtc.RtcSessionService;
import io.github.kongweiguang.voice.agent.ws.WsTextMessageContext;
import io.github.kongweiguang.voice.agent.ws.handler.WsTextMessageHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * 处理浏览器通过 WebSocket 补交的 trickle ICE candidate。
 *
 * @author kongweiguang
 */
@Component
@ConditionalOnBean(RtcSessionService.class)
@RequiredArgsConstructor
public class RtcIceCandidateWsTextMessageHandler implements WsTextMessageHandler {
    /**
     * RTC signaling 服务。
     */
    private final RtcSessionService rtcSessionService;

    @Override
    public String type() {
        return "rtc_ice_candidate";
    }

    @Override
    public void handle(WsTextMessageContext context) {
        RtcWsPayloads.RtcIceCandidateRequest request = RtcWsPayloads.iceCandidate(context.message(), context.sessionState().sessionId());
        rtcSessionService.addRemoteIceCandidate(
                request.sessionId(),
                new RTCIceCandidate(request.sdpMid(), request.sdpMLineIndex(), request.candidate())
        );
    }
}
