package io.github.kongweiguang.voice.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 语音代理后端的 Spring Boot 应用入口。
 *
 * @author kongweiguang
 */
@SpringBootApplication
public class VoiceAgentApplication {
    /**
     * Spring Boot 应用启动入口。
     */
    public static void main(String[] args) {
        SpringApplication.run(VoiceAgentApplication.class, args);
    }
}
