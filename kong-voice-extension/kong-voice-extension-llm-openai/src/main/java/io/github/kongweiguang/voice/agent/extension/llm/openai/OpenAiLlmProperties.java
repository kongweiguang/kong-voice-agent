package io.github.kongweiguang.voice.agent.extension.llm.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI LLM 接入配置。
 *
 * @param apiKey              OpenAI 接口 API Key
 * @param baseUrl             OpenAI 接口根地址，例如 https://api.openai.com/v1
 * @param chatCompletionsPath Chat Completions 接口路径
 * @param model               LLM 模型名称
 * @param systemPrompt        发送给模型的系统提示词
 * @param temperature         采样温度，空值表示交给供应商默认策略
 * @param timeoutMs           HTTP 调用超时时间，单位毫秒
 * @author kongweiguang
 */
@ConfigurationProperties(prefix = "kong-voice-agent.llm.openai")
public record OpenAiLlmProperties(String apiKey,
                                  String baseUrl,
                                  String chatCompletionsPath,
                                  String model,
                                  String systemPrompt,
                                  Double temperature,
                                  Integer timeoutMs) {
    /**
     * 归一化配置默认值，保证只配置 API Key 时即可访问 OpenAI 默认聊天端点。
     */
    public OpenAiLlmProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        if (chatCompletionsPath == null || chatCompletionsPath.isBlank()) {
            chatCompletionsPath = "/chat/completions";
        }
        if (!chatCompletionsPath.startsWith("/")) {
            chatCompletionsPath = "/" + chatCompletionsPath;
        }
        if (model == null || model.isBlank()) {
            model = "gpt-4o-mini";
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = """
                    你是一个亲切、自然且高效的语音助手。请使用用户输入的同一种语言回复。
                    回复要尽量口语化、短句优先，避免 Markdown、列表、表格、代码块和表情符号。
                    如果涉及数字、单位或符号，请优先改写成适合语音播报的自然文本。
                    """;
        }
        if (timeoutMs == null || timeoutMs <= 0) {
            timeoutMs = 30000;
        }
    }
}
