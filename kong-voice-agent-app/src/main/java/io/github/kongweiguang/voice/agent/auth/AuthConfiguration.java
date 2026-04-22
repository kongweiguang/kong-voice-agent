package io.github.kongweiguang.voice.agent.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 开启应用侧认证配置绑定。
 *
 * @author kongweiguang
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {
}
