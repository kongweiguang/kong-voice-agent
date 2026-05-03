package io.github.kongweiguang.voice.agent.extension.asr.qwen;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Qwen ASR 接入配置，基于 DashScope Java SDK 的 Qwen-ASR-Realtime 接口。
 *
 * @param enabled                        是否启用 Qwen ASR 自动装配
 * @param apiKey                         百炼 API Key
 * @param url                            Qwen-ASR-Realtime WebSocket 地址
 * @param model                          ASR 实时模型名称
 * @param language                       ASR 识别语言，留空时由模型自动判断
 * @param inputAudioFormat               输入音频格式，当前默认 pcm
 * @param enableTurnDetection            是否启用服务端 VAD
 * @param turnDetectionType              服务端 VAD 类型
 * @param turnDetectionThreshold         服务端 VAD 检测阈值
 * @param turnDetectionSilenceDurationMs 服务端 VAD 断句静音时长
 * @param timeoutMs                      等待最终转写和会话结束的超时时间，单位毫秒
 * @author kongweiguang
 */
@ConfigurationProperties(prefix = "kong-voice-agent.asr.qwen")
public record QwenAsrProperties(Boolean enabled,
                                String apiKey,
                                String url,
                                String model,
                                String language,
                                String inputAudioFormat,
                                Boolean enableTurnDetection,
                                String turnDetectionType,
                                Float turnDetectionThreshold,
                                Integer turnDetectionSilenceDurationMs,
                                Integer timeoutMs) {
    /**
     * 归一化配置默认值，保证只配置 API Key 并显式启用后即可访问百炼北京地域默认实时端点。
     */
    public QwenAsrProperties {
        if (url == null || url.isBlank()) {
            url = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
        }
        if (model == null || model.isBlank()) {
            model = "qwen3-asr-flash-realtime";
        }
        if (inputAudioFormat == null || inputAudioFormat.isBlank()) {
            inputAudioFormat = "pcm";
        }
        if (enableTurnDetection == null) {
            enableTurnDetection = false;
        }
        if (turnDetectionType == null || turnDetectionType.isBlank()) {
            turnDetectionType = "server_vad";
        }
        if (turnDetectionThreshold == null) {
            turnDetectionThreshold = 0.0f;
        }
        if (turnDetectionSilenceDurationMs == null || turnDetectionSilenceDurationMs <= 0) {
            turnDetectionSilenceDurationMs = 400;
        }
        if (timeoutMs == null || timeoutMs <= 0) {
            timeoutMs = 30000;
        }
    }
}
