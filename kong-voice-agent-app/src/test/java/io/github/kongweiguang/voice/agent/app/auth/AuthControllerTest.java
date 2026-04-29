package io.github.kongweiguang.voice.agent.app.auth;

import io.github.kongweiguang.voice.agent.app.controller.AuthController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 覆盖固定账号登录 HTTP 接口的成功、鉴权失败和请求体校验失败场景。
 *
 * @author kongweiguang
 */
@Tag("auth")
@DisplayName("认证登录接口")
class AuthControllerTest {
    /**
     * 控制器测试客户端，使用 standalone 模式只验证认证接口本身。
     */
    private MockMvc mockMvc;

    /**
     * 初始化控制器和 Bean Validation 校验器。
     */
    @BeforeEach
    void setUp() {
        AuthTokenService service = new AuthTokenService(
                new AuthProperties(new AuthProperties.FixedUser("demo-user", "demo", "demo123456")));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        this.mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(service))
                .setValidator(validator)
                .build();
    }

    /**
     * 正确账号密码应返回 Bearer token 和当前用户身份。
     */
    @Test
    @DisplayName("POST /api/auth/login 成功返回 token")
    void shouldReturnTokenWhenLoginSucceeds() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"demo","password":"demo123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", not(blankOrNullString())))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.accountId").value("demo-user"))
                .andExpect(jsonPath("$.user.username").value("demo"));
    }

    /**
     * 错误账号或密码应得到 401，调用方不能从响应中拿到 token。
     */
    @Test
    @DisplayName("POST /api/auth/login 错误账号密码返回 401")
    void shouldReturnUnauthorizedWhenCredentialsAreWrong() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"demo","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.message").value("账号或密码错误"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    /**
     * 空账号或空密码违反公开请求体约束，应在进入登录逻辑前返回 400。
     */
    @Test
    @DisplayName("POST /api/auth/login 空字段返回 400")
    void shouldReturnBadRequestWhenFieldsAreBlank() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"","password":" "}
                                """))
                .andExpect(status().isBadRequest());
    }
}
