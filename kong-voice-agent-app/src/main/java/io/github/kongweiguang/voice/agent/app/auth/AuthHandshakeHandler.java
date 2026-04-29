package io.github.kongweiguang.voice.agent.app.auth;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

/**
 * 将握手拦截器解析出的用户身份暴露为 WebSocket Principal。
 *
 * @author kongweiguang
 */
@Component
public class AuthHandshakeHandler extends DefaultHandshakeHandler {
    /**
     * 为已通过 token 鉴权的 WebSocket 连接生成 Principal。
     */
    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Object user = attributes.get(AuthHandshakeInterceptor.AUTHENTICATED_USER_ATTRIBUTE);
        if (user instanceof Principal principal) {
            return principal;
        }
        return super.determineUser(request, wsHandler, attributes);
    }
}
