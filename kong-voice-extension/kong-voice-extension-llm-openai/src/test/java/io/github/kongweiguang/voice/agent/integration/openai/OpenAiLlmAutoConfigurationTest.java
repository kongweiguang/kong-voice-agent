package io.github.kongweiguang.voice.agent.integration.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.voice.agent.llm.LlmOrchestrator;
import io.github.kongweiguang.voice.agent.extension.llm.autoconfigure.OpenAiLlmAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 OpenAI LLM 扩展的默认装配和业务覆盖能力。
 *
 * @author kongweiguang
 */
@Tag("pipeline")
@DisplayName("OpenAI LLM 自动装配")
class OpenAiLlmAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenAiLlmAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    @DisplayName("默认注册 OpenAI LLM 编排器")
    void registersDefaultLlmOrchestrator() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(LlmOrchestrator.class));
    }

    @Test
    @DisplayName("用户自定义 LLM 编排器时默认实现让位")
    void backsOffWhenCustomLlmOrchestratorExists() {
        LlmOrchestrator custom = (request, consumer) -> {
        };

        contextRunner.withBean(LlmOrchestrator.class, () -> custom)
                .run(context -> assertThat(context.getBean(LlmOrchestrator.class)).isSameAs(custom));
    }
}
