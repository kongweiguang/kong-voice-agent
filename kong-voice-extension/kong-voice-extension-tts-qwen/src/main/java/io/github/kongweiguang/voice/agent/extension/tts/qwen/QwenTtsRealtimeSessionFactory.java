package io.github.kongweiguang.voice.agent.extension.tts.qwen;

import com.google.gson.JsonObject;

import java.util.function.Consumer;

/**
 * 为每次 Qwen TTS Realtime 合成创建独立会话的工厂，便于测试替换真实 DashScope SDK。
 *
 * @author kongweiguang
 */
public interface QwenTtsRealtimeSessionFactory {
    /**
     * 创建一个新的 Qwen TTS Realtime 会话。
     *
     * @param properties    Qwen TTS 配置
     * @param eventConsumer 服务端事件回调
     * @return 可操作的实时 TTS 会话
     */
    QwenTtsRealtimeSession create(QwenTtsProperties properties, Consumer<JsonObject> eventConsumer);
}
