package io.github.kongweiguang.voice.agent.extension.transport.autoconfigure;

import io.github.kongweiguang.voice.agent.extension.transport.rtc.RtcProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * WebRTC 扩展自动配置，负责启用 RTC 属性绑定。
 *
 * @author kongweiguang
 */
@AutoConfiguration
@EnableConfigurationProperties(RtcProperties.class)
public class WebRtcAutoConfiguration {
}
