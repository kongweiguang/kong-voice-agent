package io.github.kongweiguang.voice.agent.extension.tts.qwen;

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam;
import com.google.gson.JsonObject;

import java.util.function.Consumer;

/**
 * 基于 DashScope Java SDK 的 Qwen TTS Realtime 会话工厂。
 *
 * @author kongweiguang
 */
public class DashScopeQwenTtsRealtimeSessionFactory implements QwenTtsRealtimeSessionFactory {
    /**
     * 创建 DashScope SDK 会话，并把 SDK JsonObject 事件转交给调用方。
     */
    @Override
    public QwenTtsRealtimeSession create(QwenTtsProperties properties, Consumer<JsonObject> eventConsumer) {
        QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
                .model(properties.model())
                .url(properties.url())
                .apikey(properties.apiKey())
                .build();
        QwenTtsRealtimeConfig config = realtimeConfig(properties);
        QwenTtsRealtime realtime = new QwenTtsRealtime(param, new QwenTtsRealtimeCallback() {
            @Override
            public void onEvent(JsonObject message) {
                eventConsumer.accept(message);
            }

            @Override
            public void onClose(int code, String reason) {
                JsonObject closed = new JsonObject();
                closed.addProperty("type", "connection.closed");
                closed.addProperty("code", code);
                closed.addProperty("reason", reason);
                eventConsumer.accept(closed);
            }
        });
        return new SdkQwenTtsRealtimeSession(realtime, config);
    }

    /**
     * 构造实时合成会话配置，未显式配置的可选项交给 SDK 和服务端默认值处理。
     */
    private QwenTtsRealtimeConfig realtimeConfig(QwenTtsProperties properties) {
        QwenTtsRealtimeConfig.QwenTtsRealtimeConfigBuilder<?, ?> builder = QwenTtsRealtimeConfig.builder()
                .voice(properties.voice())
                .mode(properties.mode())
                .languageType(properties.languageType())
                .responseFormat(QwenTtsRealtimeAudioFormat.valueOf(properties.responseFormat()));
        if (properties.format() != null && !properties.format().isBlank()) {
            builder.format(properties.format());
        }
        if (properties.sampleRate() != null) {
            builder.sampleRate(properties.sampleRate());
        }
        if (properties.speechRate() != null) {
            builder.speechRate(properties.speechRate());
        }
        if (properties.volume() != null) {
            builder.volume(properties.volume());
        }
        if (properties.pitchRate() != null) {
            builder.pitchRate(properties.pitchRate());
        }
        if (properties.bitRate() != null) {
            builder.bitRate(properties.bitRate());
        }
        if (properties.instructions() != null && !properties.instructions().isBlank()) {
            builder.instructions(properties.instructions());
        }
        if (properties.optimizeInstructions() != null) {
            builder.optimizeInstructions(properties.optimizeInstructions());
        }
        return builder.build();
    }

    /**
     * DashScope SDK 会话包装，统一转换受检异常为运行时异常。
     */
    private static final class SdkQwenTtsRealtimeSession implements QwenTtsRealtimeSession {
        /**
         * DashScope SDK 实时合成连接。
         */
        private final QwenTtsRealtime realtime;

        /**
         * 当前会话配置。
         */
        private final QwenTtsRealtimeConfig config;

        /**
         * 创建 SDK 会话包装。
         */
        private SdkQwenTtsRealtimeSession(QwenTtsRealtime realtime, QwenTtsRealtimeConfig config) {
            this.realtime = realtime;
            this.config = config;
        }

        @Override
        public void connect() {
            try {
                realtime.connect();
                realtime.updateSession(config);
            } catch (Exception ex) {
                throw new IllegalStateException("Qwen TTS Realtime 连接失败", ex);
            }
        }

        @Override
        public void appendText(String text) {
            realtime.appendText(text);
        }

        @Override
        public void commit() {
            realtime.commit();
        }

        @Override
        public void finish() {
            realtime.finish();
        }

        @Override
        public void close() {
            realtime.close();
        }
    }
}
