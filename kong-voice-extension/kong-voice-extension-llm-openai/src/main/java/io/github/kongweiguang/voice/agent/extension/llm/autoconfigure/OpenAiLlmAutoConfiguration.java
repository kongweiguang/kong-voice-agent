package io.github.kongweiguang.voice.agent.extension.llm.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.voice.agent.extension.llm.openai.OpenAiLlmOrchestrator;
import io.github.kongweiguang.voice.agent.extension.llm.openai.OpenAiLlmProperties;
import io.github.kongweiguang.voice.agent.llm.LlmOrchestrator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * OpenAI LLM 扩展自动配置，默认向外暴露 LLM 编排器。
 *
 * @author kongweiguang
 */
@AutoConfiguration
@EnableConfigurationProperties(OpenAiLlmProperties.class)
public class OpenAiLlmAutoConfiguration {
    /**
     * 默认 LLM 编排器，业务侧声明同类型 Bean 后会自动替换。
     */
    @Bean
    @ConditionalOnMissingBean(LlmOrchestrator.class)
    public LlmOrchestrator llmOrchestrator(OpenAiLlmProperties llmProperties, ObjectMapper objectMapper) {
        return new OpenAiLlmOrchestrator(llmProperties, objectMapper);
    }
}
