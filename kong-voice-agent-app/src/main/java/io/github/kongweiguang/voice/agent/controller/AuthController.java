package io.github.kongweiguang.voice.agent.controller;

import io.github.kongweiguang.voice.agent.auth.AuthTokenService;
import io.github.kongweiguang.voice.agent.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供应用侧固定账号登录接口。
 *
 * @author kongweiguang
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AuthController {
    /**
     * 当前固定账号 token 服务。
     */
    private final AuthTokenService authTokenService;

    /**
     * 使用固定账号密码登录，成功时返回本次登录独立 token。
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        return authTokenService.login(request.username(), request.password())
                .<ResponseEntity<?>>map(token -> ResponseEntity.ok(LoginResponse.from(token)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new AuthErrorResponse("unauthorized", "账号或密码错误")));
    }

    /**
     * 登录请求体。
     *
     * @param username 用户登录名
     * @param password 用户登录密码
     * @author kongweiguang
     */
    public record LoginRequest(
            @NotBlank(message = "username 不能为空") String username,
            @NotBlank(message = "password 不能为空") String password
    ) {
    }

    /**
     * 登录成功响应体。
     *
     * @param token     本次登录签发的 UUID token
     * @param tokenType token 类型，当前固定为 Bearer
     * @param user      当前登录用户
     * @author kongweiguang
     */
    public record LoginResponse(String token, String tokenType, AuthenticatedUser user) {
        /**
         * 从服务层 token 对象构造 HTTP 响应体。
         */
        public static LoginResponse from(AuthTokenService.LoginToken token) {
            return new LoginResponse(token.token(), "Bearer", token.user());
        }
    }

    /**
     * 认证失败响应体。
     *
     * @param code    错误码
     * @param message 用户可见错误信息
     * @author kongweiguang
     */
    public record AuthErrorResponse(String code, String message) {
    }
}
