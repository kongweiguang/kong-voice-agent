package io.github.kongweiguang.voice.agent.extension.tts.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI TTS 接入配置。
 *
 * @param apiKey         OpenAI 接口 API Key
 * @param baseUrl        OpenAI 接口根地址，例如 https://api.openai.com/v1
 * @param speechPath     Audio Speech 接口路径
 * @param model          TTS 模型名称
 * @param voice          文本转语音音色名称
 * @param responseFormat 返回音频格式，建议使用 wav 或 pcm 降低前端解码成本
 * @param instructions   音色补充指令，仅支持带指令能力的模型
 * @param timeoutMs      HTTP 调用超时时间，单位毫秒
 * @author kongweiguang
 */
@ConfigurationProperties(prefix = "kong-voice-agent.tts.openai")
public record OpenAiTtsProperties(String apiKey,
                                  String baseUrl,
                                  String speechPath,
                                  String model,
                                  String voice,
                                  String responseFormat,
                                  String instructions,
                                  Integer timeoutMs) {
    /**
     * 归一化配置默认值，保证只配置 API Key 时即可访问 OpenAI 默认语音端点。
     */
    public OpenAiTtsProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        if (speechPath == null || speechPath.isBlank()) {
            speechPath = "/audio/speech";
        }
        if (!speechPath.startsWith("/")) {
            speechPath = "/" + speechPath;
        }
        if (model == null || model.isBlank()) {
            model = "gpt-4o-mini-tts";
        }
        if (voice == null || voice.isBlank()) {
            voice = "alloy";
        }
        if (responseFormat == null || responseFormat.isBlank()) {
            responseFormat = "wav";
        }
        if (timeoutMs == null || timeoutMs <= 0) {
            timeoutMs = 30000;
        }
    }
}
