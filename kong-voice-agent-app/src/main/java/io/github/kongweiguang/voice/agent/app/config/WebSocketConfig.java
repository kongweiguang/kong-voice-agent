package io.github.kongweiguang.voice.agent.app.config;

import io.github.kongweiguang.voice.agent.app.auth.AuthHandshakeHandler;
import io.github.kongweiguang.voice.agent.app.auth.AuthHandshakeInterceptor;
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
     * WebSocket 握手阶段的 token 鉴权拦截器。
     */
    private final AuthHandshakeInterceptor authHandshakeInterceptor;

    /**
     * 将已认证用户写入 WebSocket Principal 的握手处理器。
     */
    private final AuthHandshakeHandler authHandshakeHandler;

    /**
     * 将 voice-agent WebSocket 端点注册到 Spring WebSocket 容器。
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/agent")
                .addInterceptors(authHandshakeInterceptor)
                .setHandshakeHandler(authHandshakeHandler)
                .setAllowedOrigins("*");
    }
}
