package io.github.kongweiguang.voice.agent.extension.transport.rtc.ws;

import io.github.kongweiguang.voice.agent.model.AgentEvent;
import io.github.kongweiguang.voice.agent.model.EventType;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.extension.transport.rtc.RtcSessionService;
import io.github.kongweiguang.voice.agent.extension.transport.rtc.payload.RtcSessionReadyPayload;
import io.github.kongweiguang.voice.agent.ws.WsTextMessageContext;
import io.github.kongweiguang.voice.agent.ws.handler.WsTextMessageHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动当前控制面会话的 WebRTC 媒体链路。
 *
 * @author kongweiguang
 */
@Component
@ConditionalOnBean(RtcSessionService.class)
@RequiredArgsConstructor
public class RtcStartWsTextMessageHandler implements WsTextMessageHandler {
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
        return "rtc_start";
    }

    @Override
    public void handle(WsTextMessageContext context) {
        RtcSessionService.RtcSessionDescriptor descriptor = rtcSessionService.openSession(context.sessionState());
        playbackDispatcher.send(
                context.sessionState(),
                AgentEvent.of(
                        EventType.rtc_session_ready,
                        descriptor.sessionId(),
                        context.sessionState().currentTurnId(),
                        new RtcSessionReadyPayload(
                                descriptor.sessionId(),
                                descriptor.iceServers().stream()
                                        .map(url -> new RtcSessionReadyPayload.RtcIceServerPayload(List.of(url)))
                                        .toList()
                        )
                )
        );
    }
}
