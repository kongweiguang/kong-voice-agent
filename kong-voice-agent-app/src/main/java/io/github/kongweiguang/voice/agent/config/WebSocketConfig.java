package io.github.kongweiguang.voice.agent.config;

import io.github.kongweiguang.voice.agent.ws.handler.AgentWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 voice-agent 的 WebSocket 端点。
 *
 * @author kongweiguang
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {
    /**
     * 处理 `/ws/agent` 端点所有 WebSocket 帧的核心适配器。
     */
    private final AgentWebSocketHandler handler;

    /**
     * 将 voice-agent WebSocket 端点注册到 Spring WebSocket 容器。
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/agent").setAllowedOrigins("*");
    }
}
