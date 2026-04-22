package io.github.kongweiguang.voice.agent.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * WebSocket 握手阶段的 token 鉴权拦截器。
 *
 * @author kongweiguang
 */
@Component
@RequiredArgsConstructor
public class AuthHandshakeInterceptor implements HandshakeInterceptor {
    /**
     * WebSocket attributes 中保存当前用户身份的键。
     */
    public static final String AUTHENTICATED_USER_ATTRIBUTE = "authenticatedUser";

    /**
     * token 服务，用于校验 query 参数中的 token。
     */
    private final AuthTokenService authTokenService;

    /**
     * 握手前校验 token，有效时把用户身份写入 WebSocket attributes。
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Map<String, String> map = extractParamsAsSimpleMap(request);
        return authTokenService.authenticate(map.get("token"))
                .map(user -> {
                    attributes.put(AUTHENTICATED_USER_ATTRIBUTE, user);
                    attributes.putAll(map);
                    return true;
                })
                .orElseGet(() -> {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return false;
                });
    }

    /**
     * 握手完成后当前实现无需额外清理。
     */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    /**
     * 读取 WebSocket URL query 参数。
     */
    private Map<String, String> extractParamsAsSimpleMap(ServerHttpRequest request) {
        return UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .toSingleValueMap(); // Spring 提供的便捷方法，取每个 Key 的第一个 Value
    }
}
