package io.github.kongweiguang.voice.agent.app.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖固定账号登录、失败输入和同一用户多 token 并存的服务层行为。
 *
 * @author kongweiguang
 */
@Tag("auth")
@DisplayName("认证 token 服务")
class AuthTokenServiceTest {
    /**
     * 固定账号登录成功后应签发 token，并可反查当前用户身份。
     */
    @Test
    @DisplayName("固定账号登录成功并可通过 token 查询用户")
    void shouldLoginWithFixedUserAndAuthenticateToken() {
        AuthTokenService service = new AuthTokenService(authProperties());

        Optional<AuthTokenService.LoginToken> loginToken = service.login(" demo ", "demo123456");

        assertThat(loginToken).isPresent();
        assertThat(loginToken.orElseThrow().token()).isNotBlank();
        assertThat(loginToken.orElseThrow().user())
                .isEqualTo(new AuthenticatedUser("demo-user", "demo"));
        assertThat(service.authenticate(loginToken.orElseThrow().token()))
                .contains(new AuthenticatedUser("demo-user", "demo"));
    }

    /**
     * 错误账号、错误密码和空 token 都不应获得认证身份。
     */
    @Test
    @DisplayName("固定账号登录失败时不签发 token")
    void shouldRejectWrongCredentialsAndInvalidToken() {
        AuthTokenService service = new AuthTokenService(authProperties());

        assertThat(service.login("other", "demo123456")).isEmpty();
        assertThat(service.login("demo", "wrong-password")).isEmpty();
        assertThat(service.authenticate("")).isEmpty();
        assertThat(service.authenticate("unknown-token")).isEmpty();
    }

    /**
     * 同一固定用户多次登录会得到不同 token，但 token 均映射到同一个用户身份。
     */
    @Test
    @DisplayName("同一用户多次登录会生成多个可用 token")
    void shouldAllowMultipleTokensForSameUser() {
        AuthTokenService service = new AuthTokenService(authProperties());

        AuthTokenService.LoginToken first = service.login("demo", "demo123456").orElseThrow();
        AuthTokenService.LoginToken second = service.login("demo", "demo123456").orElseThrow();

        assertThat(first.token()).isNotEqualTo(second.token());
        assertThat(first.user()).isEqualTo(second.user());
        assertThat(service.authenticate(first.token())).contains(first.user());
        assertThat(service.authenticate(second.token())).contains(second.user());
    }

    /**
     * 创建测试用固定账号配置，避免依赖外部环境变量。
     */
    private AuthProperties authProperties() {
        return new AuthProperties(new AuthProperties.FixedUser("demo-user", "demo", "demo123456"));
    }
}
