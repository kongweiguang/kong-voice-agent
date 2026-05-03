package io.github.kongweiguang.voice.agent.extension.tts.qwen;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云百炼 Qwen TTS 接入配置。
 *
 * @param apiKey               DashScope / 百炼 API Key
 * @param url                  DashScope Realtime WebSocket 地址，中国内地默认使用北京地域
 * @param model                TTS Realtime 模型名称，例如 qwen3-tts-flash-realtime
 * @param voice                文本转语音音色名称
 * @param languageType         文本语言类型，默认 Auto
 * @param mode                 交互模式，server_commit 由服务端自动判断合成时机，commit 由客户端立即提交
 * @param responseFormat       SDK 内置响应音频格式枚举名，默认 PCM_24000HZ_MONO_16BIT
 * @param format               输出音频格式，例如 pcm、wav、mp3 或 opus
 * @param sampleRate           输出音频采样率，单位 Hz
 * @param speechRate           输出音频语速，1.0 为正常语速
 * @param volume               输出音频音量，范围 0 到 100
 * @param pitchRate            输出音频语调，1.0 为默认语调
 * @param bitRate              opus 输出码率，单位 kbps
 * @param instructions         指令控制文本，仅支持 instruct 类模型
 * @param optimizeInstructions 是否开启指令优化，仅支持 instruct 类模型
 * @param timeoutMs            Realtime 会话等待超时时间，单位毫秒
 * @author kongweiguang
 */
@ConfigurationProperties(prefix = "kong-voice-agent.tts.qwen")
public record QwenTtsProperties(String apiKey,
                                String url,
                                String model,
                                String voice,
                                String languageType,
                                String mode,
                                String responseFormat,
                                String format,
                                Integer sampleRate,
                                Float speechRate,
                                Integer volume,
                                Float pitchRate,
                                Integer bitRate,
                                String instructions,
                                Boolean optimizeInstructions,
                                Integer timeoutMs) {
    /**
     * 归一化配置默认值，保证只配置 API Key 时即可访问北京地域的 Qwen TTS Realtime 端点。
     */
    public QwenTtsProperties {
        if (url == null || url.isBlank()) {
            url = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
        }
        if (model == null || model.isBlank()) {
            model = "qwen3-tts-flash-realtime";
        }
        if (voice == null || voice.isBlank()) {
            voice = "Cherry";
        }
        if (languageType == null || languageType.isBlank()) {
            languageType = "Auto";
        }
        if (mode == null || mode.isBlank()) {
            mode = "server_commit";
        }
        if (responseFormat == null || responseFormat.isBlank()) {
            responseFormat = "PCM_24000HZ_MONO_16BIT";
        }
        if (timeoutMs == null || timeoutMs <= 0) {
            timeoutMs = 30000;
        }
    }
}
