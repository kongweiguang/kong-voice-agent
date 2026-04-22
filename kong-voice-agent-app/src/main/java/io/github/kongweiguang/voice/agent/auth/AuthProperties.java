package io.github.kongweiguang.voice.agent.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定应用侧固定账号认证配置。
 *
 * @param fixedUser 当前内置的固定登录账号配置
 * @author kongweiguang
 */
@Validated
@ConfigurationProperties(prefix = "kong-voice-agent.auth")
public record AuthProperties(@Valid FixedUser fixedUser) {
    /**
     * 固定登录账号配置，生产部署应通过环境变量覆盖默认密码。
     *
     * @param accountId 固定用户在服务端内部使用的稳定账号标识
     * @param username  固定用户登录名
     * @param password  固定用户登录密码
     * @author kongweiguang
     */
    public record FixedUser(
            @NotBlank(message = "account-id 不能为空") String accountId,
            @NotBlank(message = "username 不能为空") String username,
            @NotBlank(message = "password 不能为空") String password
    ) {
        /**
         * 创建固定用户配置并归一化空白字符。
         */
        public FixedUser {
            accountId = accountId == null ? null : accountId.trim();
            username = username == null ? null : username.trim();
        }
    }
}
