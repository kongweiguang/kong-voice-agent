package io.github.kongweiguang.voice.agent.integration.tts;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DashScope Qwen-TTS 接入配置，默认使用 multimodal-generation 同步接口。
 *
 * @param apiKey           DashScope API Key，建议通过 DASHSCOPE_API_KEY 或 KONG_VOICE_AGENT_DASHSCOPE_API_KEY 注入
 * @param baseUrl          DashScope 服务根地址，例如 https://dashscope.aliyuncs.com
 * @param generationPath   Qwen-TTS multimodal generation 接口路径
 * @param model            Qwen-TTS 模型名称，例如 qwen3-tts-flash
 * @param voice            DashScope 文本转语音音色名称，例如 Cherry
 * @param languageType     语言类型，常用值为 Chinese、English 或 Auto
 * @param streamingEnabled 是否启用 DashScope SSE 流式 TTS 输出，开启后会把远端音频分片实时下发
 * @param timeoutMs        HTTP 调用超时时间，单位毫秒
 * @author kongweiguang
 */
@ConfigurationProperties(prefix = "kong-voice-agent.tts.dashscope")
public record DashScopeTtsProperties(String apiKey,
                                      String baseUrl,
                                      String generationPath,
                                      String model,
                                      String voice,
                                      String languageType,
                                      Boolean streamingEnabled,
                                      Integer timeoutMs) {
    /**
     * 归一化配置默认值，保证只配置 API Key 时即可访问 DashScope 默认端点。
     */
    public DashScopeTtsProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://dashscope.aliyuncs.com";
        }
        if (generationPath == null || generationPath.isBlank()) {
            generationPath = "/api/v1/services/aigc/multimodal-generation/generation";
        }
        if (!generationPath.startsWith("/")) {
            generationPath = "/" + generationPath;
        }
        if (model == null || model.isBlank()) {
            model = "qwen3-tts-flash";
        }
        if (voice == null || voice.isBlank()) {
            voice = "Cherry";
        }
        if (languageType == null || languageType.isBlank()) {
            languageType = "Chinese";
        }
        if (streamingEnabled == null) {
            streamingEnabled = true;
        }
        if (timeoutMs == null || timeoutMs <= 0) {
            timeoutMs = 30000;
        }
    }
}
