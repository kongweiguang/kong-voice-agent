package io.github.kongweiguang.voice.agent.extension.transport.rtc.ws;

import com.fasterxml.jackson.databind.JsonNode;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSdpType;
import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.extension.transport.rtc.RtcSessionService;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.util.JsonUtils;
import io.github.kongweiguang.voice.agent.ws.WsMessage;
import io.github.kongweiguang.voice.agent.ws.WsTextMessageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 WebRTC WebSocket signaling handler 的协议解析与事件发送行为。
 *
 * @author kongweiguang
 */
@Tag("protocol")
@DisplayName("WebRTC WebSocket signaling")
class RtcWsTextMessageHandlerTest {
    @Test
    @DisplayName("rtc_start 返回 rtc_session_ready")
    void startsRtcSessionOverWebSocket() throws Exception {
        RtcSessionService rtcSessionService = mock(RtcSessionService.class);
        PlaybackDispatcher dispatcher = new PlaybackDispatcher();
        SessionState sessionState = sessionState("sess_ws");
        CapturingWebSocketSession webSocketSession = new CapturingWebSocketSession();
        sessionState.bindControlWebSocketSession(webSocketSession);
        when(rtcSessionService.openSession(sessionState))
                .thenReturn(new RtcSessionService.RtcSessionDescriptor("sess_ws", List.of("stun:stun.l.google.com:19302")));

        new RtcStartWsTextMessageHandler(rtcSessionService, dispatcher)
                .handle(new WsTextMessageContext(webSocketSession, sessionState, new WsMessage("rtc_start", null)));

        JsonNode event = webSocketSession.sentEvents().getFirst();
        assertThat(event.get("type").asText()).isEqualTo("rtc_session_ready");
        assertThat(event.at("/payload/sessionId").asText()).isEqualTo("sess_ws");
        assertThat(event.at("/payload/iceServers/0/urls/0").asText()).isEqualTo("stun:stun.l.google.com:19302");
    }

    @Test
    @DisplayName("rtc_offer 返回 rtc_answer")
    void returnsRtcAnswerOverWebSocket() throws Exception {
        RtcSessionService rtcSessionService = mock(RtcSessionService.class);
        PlaybackDispatcher dispatcher = new PlaybackDispatcher();
        SessionState sessionState = sessionState("sess_ws");
        CapturingWebSocketSession webSocketSession = new CapturingWebSocketSession();
        sessionState.bindControlWebSocketSession(webSocketSession);
        when(rtcSessionService.handleOffer(eq("sess_ws"), any(RTCSessionDescription.class)))
                .thenReturn(new RTCSessionDescription(RTCSdpType.ANSWER, "answer-sdp"));

        new RtcOfferWsTextMessageHandler(rtcSessionService, dispatcher)
                .handle(new WsTextMessageContext(
                        webSocketSession,
                        sessionState,
                        new WsMessage("rtc_offer", JsonUtils.MAPPER.readTree("""
                                {"sessionId":"sess_ws","type":"offer","sdp":"offer-sdp"}
                                """))
                ));

        JsonNode event = webSocketSession.sentEvents().getFirst();
        assertThat(event.get("type").asText()).isEqualTo("rtc_answer");
        assertThat(event.at("/payload/type").asText()).isEqualTo("ANSWER");
        assertThat(event.at("/payload/sdp").asText()).isEqualTo("answer-sdp");
    }

    @Test
    @DisplayName("rtc_ice_candidate 转发到 RTC 服务")
    void forwardsRtcIceCandidate() throws Exception {
        RtcSessionService rtcSessionService = mock(RtcSessionService.class);
        SessionState sessionState = sessionState("sess_ws");
        CapturingWebSocketSession webSocketSession = new CapturingWebSocketSession();

        new RtcIceCandidateWsTextMessageHandler(rtcSessionService)
                .handle(new WsTextMessageContext(
                        webSocketSession,
                        sessionState,
                        new WsMessage("rtc_ice_candidate", JsonUtils.MAPPER.readTree("""
                                {"sessionId":"sess_ws","sdpMid":"0","sdpMLineIndex":0,"candidate":"candidate:1 1 UDP 1 127.0.0.1 1234 typ host"}
                                """))
                ));

        verify(rtcSessionService).addRemoteIceCandidate(eq("sess_ws"), any(RTCIceCandidate.class));
    }

    @Test
    @DisplayName("rtc_close 关闭当前 RTC 会话")
    void closesRtcSessionOverWebSocket() throws Exception {
        RtcSessionService rtcSessionService = mock(RtcSessionService.class);
        SessionState sessionState = sessionState("sess_ws");
        CapturingWebSocketSession webSocketSession = new CapturingWebSocketSession();

        new RtcCloseWsTextMessageHandler(rtcSessionService)
                .handle(new WsTextMessageContext(
                        webSocketSession,
                        sessionState,
                        new WsMessage("rtc_close", JsonUtils.MAPPER.readTree("""
                                {"sessionId":"sess_ws"}
                                """))
                ));

        verify(rtcSessionService).closeSession("sess_ws");
    }

    @Test
    @DisplayName("rtc_close 省略 sessionId 时关闭当前控制面会话")
    void closesCurrentRtcSessionWhenPayloadOmitsSessionId() throws Exception {
        RtcSessionService rtcSessionService = mock(RtcSessionService.class);
        SessionState sessionState = sessionState("sess_ws");
        CapturingWebSocketSession webSocketSession = new CapturingWebSocketSession();

        new RtcCloseWsTextMessageHandler(rtcSessionService)
                .handle(new WsTextMessageContext(
                        webSocketSession,
                        sessionState,
                        new WsMessage("rtc_close", JsonUtils.MAPPER.readTree("{}"))
                ));

        verify(rtcSessionService).closeSession("sess_ws");
    }

    private SessionState sessionState(String sessionId) {
        return new SessionState(sessionId, AudioFormatSpec.DEFAULT, (ignored, format) -> new NoopStreamingAsrAdapter(), eouConfig());
    }

    private EouConfig eouConfig() {
        return new EouConfig(true, null, null, null, true, 0.5, 500, 1600, 300, "zh");
    }

    /**
     * 仅满足 SessionState 构造依赖的空 ASR。
     */
    private static final class NoopStreamingAsrAdapter implements StreamingAsrAdapter {
        @Override
        public Optional<AsrUpdate> acceptAudio(String turnId, byte[] pcm) {
            return Optional.empty();
        }

        @Override
        public AsrUpdate commitTurn(String turnId) {
            return AsrUpdate.finalUpdate(turnId, "");
        }

        @Override
        public void close() {
        }
    }

    /**
     * 收集 handler 发送的下行消息，便于断言 signaling 事件。
     */
    private static final class CapturingWebSocketSession implements WebSocketSession {
        private final String id = UUID.randomUUID().toString();
        private final List<String> sent = new ArrayList<>();

        List<JsonNode> sentEvents() throws IOException {
            List<JsonNode> events = new ArrayList<>();
            for (String message : sent) {
                events.add(JsonUtils.MAPPER.readTree(message));
            }
            return events;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/ws/agent");
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return new HttpHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return Map.of();
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 0;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 0;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            sent.add(String.valueOf(message.getPayload()));
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }

        @Override
        public void close(CloseStatus status) {
        }
    }
}
