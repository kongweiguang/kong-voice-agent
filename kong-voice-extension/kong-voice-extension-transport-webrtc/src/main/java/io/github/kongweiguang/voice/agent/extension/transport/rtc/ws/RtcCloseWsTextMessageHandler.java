package io.github.kongweiguang.voice.agent.extension.transport.rtc.ws;

import io.github.kongweiguang.voice.agent.extension.transport.rtc.RtcSessionService;
import io.github.kongweiguang.voice.agent.ws.WsTextMessageContext;
import io.github.kongweiguang.voice.agent.ws.handler.WsTextMessageHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * 关闭当前控制面会话挂载的 RTC 运行态。
 *
 * @author kongweiguang
 */
@Component
@ConditionalOnBean(RtcSessionService.class)
@RequiredArgsConstructor
public class RtcCloseWsTextMessageHandler implements WsTextMessageHandler {
    /**
     * RTC signaling 服务。
     */
    private final RtcSessionService rtcSessionService;

    @Override
    public String type() {
        return "rtc_close";
    }

    @Override
    public void handle(WsTextMessageContext context) {
        RtcWsPayloads.RtcCloseRequest request = RtcWsPayloads.close(context.message(), context.sessionState().sessionId());
        rtcSessionService.closeSession(request.sessionId());
    }
}
