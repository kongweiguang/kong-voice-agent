package io.github.kongweiguang.voice.agent.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 负责固定账号校验和进程内 token 生命周期管理。
 *
 * @author kongweiguang
 */
@Service
@RequiredArgsConstructor
public class AuthTokenService {
    /**
     * token 到当前用户身份的映射；当前实现仅在本进程内有效，重启后全部失效。
     */
    private final ConcurrentMap<String, AuthenticatedUser> tokenUsers = new ConcurrentHashMap<>();

    /**
     * 应用侧固定账号配置。
     */
    private final AuthProperties authProperties;

    /**
     * 校验固定账号密码，成功时签发新的 UUID token。
     */
    public Optional<LoginToken> login(String username, String password) {
        AuthProperties.FixedUser fixedUser = authProperties.fixedUser();
        String normalizedUsername = username == null ? "" : username.trim();
        if (!fixedUser.username().equals(normalizedUsername) || !fixedUser.password().equals(password)) {
            return Optional.empty();
        }
        AuthenticatedUser user = new AuthenticatedUser(fixedUser.accountId(), fixedUser.username());
        String token = UUID.randomUUID().toString();
        // token 是后续 WebSocket 握手的入口凭证，当前仅保存在本进程内存中。
        tokenUsers.put(token, user);
        return Optional.of(new LoginToken(token, user));
    }

    /**
     * 根据 token 查询当前用户身份。
     */
    public Optional<AuthenticatedUser> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokenUsers.get(token.trim()));
    }

    /**
     * 登录成功后返回给接口层的 token 和用户身份。
     *
     * @param token UUID token 字符串
     * @param user  token 对应的用户身份
     * @author kongweiguang
     */
    public record LoginToken(String token, AuthenticatedUser user) {
    }
}
