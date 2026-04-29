package io.github.kongweiguang.voice.agent.extension.transport.rtc.ws;

import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSdpType;
import io.github.kongweiguang.voice.agent.model.AgentEvent;
import io.github.kongweiguang.voice.agent.model.EventType;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.extension.transport.rtc.RtcSessionService;
import io.github.kongweiguang.voice.agent.extension.transport.rtc.payload.RtcAnswerPayload;
import io.github.kongweiguang.voice.agent.ws.WsTextMessageContext;
import io.github.kongweiguang.voice.agent.ws.handler.WsTextMessageHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * 处理浏览器通过 WebSocket 提交的 WebRTC offer。
 *
 * @author kongweiguang
 */
@Component
@ConditionalOnBean(RtcSessionService.class)
@RequiredArgsConstructor
public class RtcOfferWsTextMessageHandler implements WsTextMessageHandler {
    /**
     * RTC signaling 服务。
     */
    private final RtcSessionService rtcSessionService;

    /**
     * 统一控制面事件发送器。
     */
    private final PlaybackDispatcher playbackDispatcher;

    @Override
    public String type() {
        return "rtc_offer";
    }

    @Override
    public void handle(WsTextMessageContext context) {
        RtcWsPayloads.RtcOfferRequest request = RtcWsPayloads.offer(context.message(), context.sessionState().sessionId());
        RTCSessionDescription answer = rtcSessionService.handleOffer(
                request.sessionId(),
                new RTCSessionDescription(RTCSdpType.valueOf(request.type().trim().toUpperCase()), request.sdp())
        );
        playbackDispatcher.send(
                context.sessionState(),
                AgentEvent.of(
                        EventType.rtc_answer,
                        request.sessionId(),
                        context.sessionState().currentTurnId(),
                        new RtcAnswerPayload(answer.sdpType.name(), answer.sdp)
                )
        );
    }
}
