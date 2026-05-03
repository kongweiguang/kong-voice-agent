package io.github.kongweiguang.voice.agent.extension.asr.qwen;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;

import java.util.function.Consumer;

/**
 * Qwen-ASR-Realtime 会话工厂，为每个语音 turn 创建独立实时识别连接。
 *
 * @author kongweiguang
 */
public interface QwenRealtimeSessionFactory {
    /**
     * 创建实时识别会话，服务端事件会通过 eventConsumer 回调给适配器。
     */
    QwenRealtimeSession create(QwenAsrProperties properties, AudioFormatSpec format, Consumer<JsonNode> eventConsumer);
}
