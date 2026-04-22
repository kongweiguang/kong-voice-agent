package io.github.kongweiguang.voice.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 覆盖应用完整 Spring 上下文启动，防止依赖 BOM 覆盖导致自动配置类在启动期解析失败。
 *
 * @author kongweiguang
 */
@Tag("application")
@DisplayName("应用上下文启动")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "kong-voice-agent.onnx.gpu-enabled=false",
                "kong-voice-agent.onnx.fallback-to-cpu=true"
        })
class VoiceAgentApplicationTest {
    /**
     * Spring Boot 能成功加载全部自动配置和业务 Bean 即视为启动烟测通过。
     */
    @Test
    @DisplayName("Spring Boot 应用上下文可以启动")
    void shouldStartApplicationContext() {
    }
}
