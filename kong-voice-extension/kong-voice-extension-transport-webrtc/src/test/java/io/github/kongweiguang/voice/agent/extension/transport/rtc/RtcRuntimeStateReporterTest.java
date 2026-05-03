package io.github.kongweiguang.voice.agent.extension.transport.rtc;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.v1.json.Json;
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

/**
 * 保护 RTC 运行态状态事件，避免后续迭代把联调所需的可观测信号再悄悄删掉。
 *
 * @author kongweiguang
 */
@Tag("protocol")
@DisplayName("RTC 运行态状态事件")
class RtcRuntimeStateReporterTest {
    @Test
    @DisplayName("关键 RTC 状态会下发 rtc_state_changed 事件")
    void emitsRtcStateChangedEvents() throws Exception {
        PlaybackDispatcher dispatcher = new PlaybackDispatcher();
        SessionState sessionState = sessionState("sess_rtc");
        CapturingWebSocketSession webSocketSession = new CapturingWebSocketSession();
        sessionState.bindControlWebSocketSession(webSocketSession);
        RtcRuntimeStateReporter reporter = new RtcRuntimeStateReporter(sessionState, dispatcher);

        reporter.sessionOpened();
        reporter.trackBound();
        reporter.mediaFlowing();
        reporter.closed("rtc_close");

        List<JsonNode> events = webSocketSession.sentEvents();
        assertThat(events).hasSize(4);
        assertThat(events)
                .extracting(event -> event.get("type").asText())
                .containsOnly("rtc_state_changed");
        assertThat(events.get(0).at("/payload/state").asText()).isEqualTo("session_opened");
        assertThat(events.get(0).at("/payload/source").asText()).isEqualTo("session");
        assertThat(events.get(1).at("/payload/state").asText()).isEqualTo("track_bound");
        assertThat(events.get(2).at("/payload/state").asText()).isEqualTo("media_flowing");
        assertThat(events.get(3).at("/payload/detail").asText()).isEqualTo("rtc_close");
    }

    @Test
    @DisplayName("连续重复的 RTC 状态不会反复下发")
    void deduplicatesRepeatedRtcStates() throws Exception {
        PlaybackDispatcher dispatcher = new PlaybackDispatcher();
        SessionState sessionState = sessionState("sess_rtc");
        CapturingWebSocketSession webSocketSession = new CapturingWebSocketSession();
        sessionState.bindControlWebSocketSession(webSocketSession);
        RtcRuntimeStateReporter reporter = new RtcRuntimeStateReporter(sessionState, dispatcher);

        reporter.mediaFlowing();
        reporter.mediaFlowing();

        assertThat(webSocketSession.sentEvents()).hasSize(1);
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
     * 收集测试过程中的协议下行事件。
     */
    private static final class CapturingWebSocketSession implements WebSocketSession {
        private final String id = UUID.randomUUID().toString();
        private final List<String> sent = new ArrayList<>();

        List<JsonNode> sentEvents() throws IOException {
            List<JsonNode> events = new ArrayList<>();
            for (String message : sent) {
                events.add(Json.node(message));
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
