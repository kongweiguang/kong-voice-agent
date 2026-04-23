package io.github.kongweiguang.voice.agent.integration.asr;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DashScope Qwen-ASR 接入配置，默认使用 OpenAI 兼容 Chat Completions 端点提交 base64 WAV 音频。
 *
 * @param apiKey              DashScope API Key，建议通过 DASHSCOPE_API_KEY 或 KONG_VOICE_AGENT_DASHSCOPE_API_KEY 注入
 * @param baseUrl             OpenAI 兼容模式根地址，例如 https://dashscope.aliyuncs.com/compatible-mode/v1
 * @param chatCompletionsPath Chat Completions 接口路径
 * @param model               Qwen-ASR 模型名称，例如 qwen3-asr-flash
 * @param language            ASR 识别语言，留空时由模型自动判断
 * @param enableItn           是否启用逆文本规范化，例如把数字读法转为阿拉伯数字
 * @param timeoutMs           HTTP 调用超时时间，单位毫秒
 * @author kongweiguang
 */
@ConfigurationProperties(prefix = "kong-voice-agent.asr.dashscope")
public record DashScopeAsrProperties(String apiKey,
                                      String baseUrl,
                                      String chatCompletionsPath,
                                      String model,
                                      String language,
                                      Boolean enableItn,
                                      Integer timeoutMs) {
    /**
     * 归一化配置默认值，保证只配置 API Key 时即可访问 DashScope 默认端点。
     */
    public DashScopeAsrProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        if (chatCompletionsPath == null || chatCompletionsPath.isBlank()) {
            chatCompletionsPath = "/chat/completions";
        }
        if (!chatCompletionsPath.startsWith("/")) {
            chatCompletionsPath = "/" + chatCompletionsPath;
        }
        if (model == null || model.isBlank()) {
            model = "qwen3-asr-flash";
        }
        if (enableItn == null) {
            enableItn = true;
        }
        if (timeoutMs == null || timeoutMs <= 0) {
            timeoutMs = 15000;
        }
    }
}
