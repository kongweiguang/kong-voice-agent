package io.github.kongweiguang.voice.agent.extension.asr.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI ASR 接入配置，直接对接 Audio Transcriptions 接口。
 *
 * @param apiKey             OpenAI 接口 API Key
 * @param baseUrl            OpenAI 接口根地址，例如 https://api.openai.com/v1
 * @param transcriptionsPath Audio Transcriptions 接口路径
 * @param model              ASR 模型名称
 * @param language           ASR 识别语言，留空时由模型自动判断
 * @param timeoutMs          HTTP 调用超时时间，单位毫秒
 * @author kongweiguang
 */
@ConfigurationProperties(prefix = "kong-voice-agent.asr.openai")
public record OpenAiAsrProperties(String apiKey,
                                  String baseUrl,
                                  String transcriptionsPath,
                                  String model,
                                  String language,
                                  Integer timeoutMs) {
    /**
     * 归一化配置默认值，保证只配置 API Key 时即可访问 OpenAI 默认转写端点。
     */
    public OpenAiAsrProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        if (transcriptionsPath == null || transcriptionsPath.isBlank()) {
            transcriptionsPath = "/audio/transcriptions";
        }
        if (!transcriptionsPath.startsWith("/")) {
            transcriptionsPath = "/" + transcriptionsPath;
        }
        if (model == null || model.isBlank()) {
            model = "gpt-4o-mini-transcribe";
        }
        if (timeoutMs == null || timeoutMs <= 0) {
            timeoutMs = 15000;
        }
    }
}
