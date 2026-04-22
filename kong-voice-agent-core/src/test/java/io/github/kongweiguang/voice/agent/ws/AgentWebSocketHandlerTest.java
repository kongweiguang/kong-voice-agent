package io.github.kongweiguang.voice.agent.ws;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.service.VoicePipelineService;
import io.github.kongweiguang.voice.agent.session.SessionManager;
import io.github.kongweiguang.voice.agent.support.NoopStreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.util.JsonUtils;
import io.github.kongweiguang.voice.agent.ws.handler.AgentWebSocketHandler;
import io.github.kongweiguang.voice.agent.ws.handler.WsTextMessageHandler;
import io.github.kongweiguang.voice.agent.ws.handler.WsTextMessageHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 验证 WebSocket handler 只负责解析和委托策略注册表。
 *
 * @author kongweiguang
 */
@Tag("protocol")
@DisplayName("WebSocket 文本消息入口")
class AgentWebSocketHandlerTest {
    @Test
    @DisplayName("文本消息委托给策略注册表")
    void delegatesTextMessageToRegistry() throws Exception {
        RecordingHandler textHandler = new RecordingHandler(WsMessageType.text.name());
        WsTextMessageHandlerRegistry registry = new WsTextMessageHandlerRegistry(List.of(textHandler));
        AgentWebSocketHandler handler = new AgentWebSocketHandler(
                new SessionManager((sessionId, format) -> new NoopStreamingAsrAdapter(), AudioFormatSpec.DEFAULT, eouConfig()),
                mock(VoicePipelineService.class),
                new PlaybackDispatcher(),
                registry
        );
        CapturingWebSocketSession ws = new CapturingWebSocketSession();

        handler.handleMessage(ws, new TextMessage("""
                {"type":"text","payload":{"text":"你好"}}
                """));

        assertThat(textHandler.handledMessages).hasSize(1);
        assertThat(textHandler.handledMessages.getFirst().message().textPayload()).isEqualTo("你好");
        assertThat(ws.sent).isEmpty();
    }

    @Test
    @DisplayName("策略异常会转换为 error 事件")
    void sendsErrorWhenRegistryRejectsMessage() throws Exception {
        AgentWebSocketHandler handler = new AgentWebSocketHandler(
                new SessionManager((sessionId, format) -> new NoopStreamingAsrAdapter(), AudioFormatSpec.DEFAULT, eouConfig()),
                mock(VoicePipelineService.class),
                new PlaybackDispatcher(),
                new WsTextMessageHandlerRegistry(List.of())
        );
        CapturingWebSocketSession ws = new CapturingWebSocketSession();

        handler.handleMessage(ws, new TextMessage("""
                {"type":"unknown","payload":{}}
                """));

        JsonNode event = JsonUtils.MAPPER.readTree(ws.sent.getFirst());
        assertThat(event.get("type").asText()).isEqualTo("error");
        assertThat(event.at("/payload/code").asText()).isEqualTo("bad_message");
        assertThat(event.at("/payload/message").asText()).isEqualTo("unsupported type: unknown");
    }

    /**
     * 记录 handler 收到的上下文，证明入口没有自行分支处理 type。
     */
    private static final class RecordingHandler implements WsTextMessageHandler {
        private final String type;
        private final List<WsTextMessageContext> handledMessages = new ArrayList<>();

        private RecordingHandler(String type) {
            this.type = type;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public void handle(WsTextMessageContext context) {
            handledMessages.add(context);
        }
    }

    /**
     * 收集 handler 发送的下行消息，便于断言错误事件。
     */
    private static final class CapturingWebSocketSession implements WebSocketSession {
        private final String id = UUID.randomUUID().toString();
        private final List<String> sent = new ArrayList<>();

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
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            sent.add(String.valueOf(message.getPayload()));
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() throws IOException {
        }

        @Override
        public void close(CloseStatus status) throws IOException {
        }
    }

    private EouConfig eouConfig() {
        return new EouConfig(true, null, null, null, true, 0.5, 500, 1600, 300, "zh");
    }
}
