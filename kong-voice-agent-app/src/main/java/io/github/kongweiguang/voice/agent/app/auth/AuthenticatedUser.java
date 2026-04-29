package io.github.kongweiguang.voice.agent.app.auth;

import java.security.Principal;

/**
 * 登录成功后的用户身份，同一账号的多个 token 都映射到同一个身份。
 *
 * @param accountId 服务端内部稳定账号标识
 * @param username  用户登录名
 * @author kongweiguang
 */
public record AuthenticatedUser(String accountId, String username) implements Principal {
    /**
     * WebSocket Principal 使用的名称，保持与登录账号一致。
     */
    @Override
    public String getName() {
        return username;
    }
}
