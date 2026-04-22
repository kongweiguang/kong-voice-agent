package io.github.kongweiguang.voice.agent.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 WebSocket 握手 token 鉴权和认证身份写入 attributes 的边界行为。
 *
 * @author kongweiguang
 */
@Tag("auth")
@DisplayName("认证握手拦截器")
class AuthHandshakeInterceptorTest {
    /**
     * 缺少 token 时握手应被拒绝，并返回 401。
     */
    @Test
    @DisplayName("缺少 token 时拒绝握手")
    void shouldRejectHandshakeWithoutToken() {
        AuthHandshakeInterceptor interceptor = new AuthHandshakeInterceptor(authTokenService());
        Map<String, Object> attributes = new HashMap<>();
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        boolean allowed = interceptor.beforeHandshake(
                request("/ws/agent"),
                new ServletServerHttpResponse(servletResponse),
                null,
                attributes);

        assertThat(allowed).isFalse();
        assertThat(servletResponse.getStatus()).isEqualTo(401);
        assertThat(attributes).doesNotContainKey(AuthHandshakeInterceptor.AUTHENTICATED_USER_ATTRIBUTE);
    }

    /**
     * 错误 token 不应放行，也不能把用户身份写入 WebSocket attributes。
     */
    @Test
    @DisplayName("错误 token 时拒绝握手")
    void shouldRejectHandshakeWithWrongToken() {
        AuthHandshakeInterceptor interceptor = new AuthHandshakeInterceptor(authTokenService());
        Map<String, Object> attributes = new HashMap<>();
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        boolean allowed = interceptor.beforeHandshake(
                request("/ws/agent?token=wrong-token"),
                new ServletServerHttpResponse(servletResponse),
                null,
                attributes);

        assertThat(allowed).isFalse();
        assertThat(servletResponse.getStatus()).isEqualTo(401);
        assertThat(attributes).doesNotContainKey(AuthHandshakeInterceptor.AUTHENTICATED_USER_ATTRIBUTE);
    }

    /**
     * 有效 token 应放行握手，并把认证用户写入 attributes 供后续 Principal 构造使用。
     */
    @Test
    @DisplayName("有效 token 时放行并写入 attributes")
    void shouldAllowHandshakeWithValidTokenAndWriteAttributes() {
        AuthTokenService service = authTokenService();
        AuthTokenService.LoginToken loginToken = service.login("demo", "demo123456").orElseThrow();
        AuthHandshakeInterceptor interceptor = new AuthHandshakeInterceptor(service);
        Map<String, Object> attributes = new HashMap<>();

        boolean allowed = interceptor.beforeHandshake(
                request("/ws/agent?token=" + loginToken.token()),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                null,
                attributes);

        assertThat(allowed).isTrue();
        assertThat(attributes)
                .containsEntry(AuthHandshakeInterceptor.AUTHENTICATED_USER_ATTRIBUTE, loginToken.user());
    }

    /**
     * 同一用户的多个 token 都应独立有效，避免二次登录误使旧连接无法握手。
     */
    @Test
    @DisplayName("同一用户多个 token 均可放行")
    void shouldAllowMultipleTokensForSameUser() {
        AuthTokenService service = authTokenService();
        AuthTokenService.LoginToken first = service.login("demo", "demo123456").orElseThrow();
        AuthTokenService.LoginToken second = service.login("demo", "demo123456").orElseThrow();
        AuthHandshakeInterceptor interceptor = new AuthHandshakeInterceptor(service);

        Map<String, Object> firstAttributes = new HashMap<>();
        Map<String, Object> secondAttributes = new HashMap<>();

        boolean firstAllowed = interceptor.beforeHandshake(
                request("/ws/agent?token=" + first.token()),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                null,
                firstAttributes);
        boolean secondAllowed = interceptor.beforeHandshake(
                request("/ws/agent?token=" + second.token()),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                null,
                secondAttributes);

        assertThat(firstAllowed).isTrue();
        assertThat(secondAllowed).isTrue();
        assertThat(first.token()).isNotEqualTo(second.token());
        assertThat(firstAttributes)
                .containsEntry(AuthHandshakeInterceptor.AUTHENTICATED_USER_ATTRIBUTE, first.user());
        assertThat(secondAttributes)
                .containsEntry(AuthHandshakeInterceptor.AUTHENTICATED_USER_ATTRIBUTE, second.user());
    }

    /**
     * 创建只包含认证配置的服务实例，测试不依赖应用上下文。
     */
    private AuthTokenService authTokenService() {
        return new AuthTokenService(
                new AuthProperties(new AuthProperties.FixedUser("demo-user", "demo", "demo123456")));
    }

    /**
     * 根据目标地址构造握手请求。
     */
    private ServletServerHttpRequest request(String uri) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", uri);
        servletRequest.setServerName("localhost");
        servletRequest.setServerPort(9877);
        return new ServletServerHttpRequest(servletRequest);
    }
}
