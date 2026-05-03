package io.github.kongweiguang.voice.agent.extension.asr.qwen;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonObject;
import io.github.kongweiguang.v1.json.Json;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;

import java.util.Collections;
import java.util.function.Consumer;

/**
 * 基于 DashScope Java SDK 的 Qwen-ASR-Realtime 会话工厂。
 *
 * @author kongweiguang
 */
public class DashScopeQwenRealtimeSessionFactory implements QwenRealtimeSessionFactory {
    /**
     * 创建 DashScope SDK 会话，并把 SDK JsonObject 事件转换为项目统一 JsonNode。
     */
    @Override
    public QwenRealtimeSession create(QwenAsrProperties properties, AudioFormatSpec format, Consumer<JsonNode> eventConsumer) {
        OmniRealtimeParam param = OmniRealtimeParam.builder()
                .model(properties.model())
                .url(properties.url())
                .apikey(properties.apiKey())
                .build();
        OmniRealtimeConfig config = realtimeConfig(properties, format);
        OmniRealtimeConversation conversation = new OmniRealtimeConversation(param, new OmniRealtimeCallback() {
            @Override
            public void onEvent(JsonObject message) {
                eventConsumer.accept(Json.node(message.toString()));
            }

            @Override
            public void onClose(int code, String reason) {
                eventConsumer.accept(Json.node("""
                        {"type":"connection.closed","code":%d,"reason":%s}
                        """.formatted(code, Json.str(reason))));
            }
        });
        return new SdkQwenRealtimeSession(conversation, config);
    }

    /**
     * 构造实时识别会话配置，音频采样率跟随当前后端 session 的 AudioFormatSpec。
     */
    OmniRealtimeConfig realtimeConfig(QwenAsrProperties properties, AudioFormatSpec format) {
        OmniRealtimeTranscriptionParam transcriptionParam = new OmniRealtimeTranscriptionParam();
        transcriptionParam.setLanguage(properties.language());
        transcriptionParam.setInputSampleRate(format.sampleRate());
        transcriptionParam.setInputAudioFormat(properties.inputAudioFormat());
        OmniRealtimeConfig.OmniRealtimeConfigBuilder<?, ?> builder = OmniRealtimeConfig.builder()
                .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
                .transcriptionConfig(transcriptionParam);
        if (Boolean.TRUE.equals(properties.enableTurnDetection())) {
            // 服务端 VAD 模式需要明确下发 server_vad 及断句阈值。
            builder.enableTurnDetection(true)
                    .turnDetectionType(properties.turnDetectionType())
                    .turnDetectionThreshold(properties.turnDetectionThreshold())
                    .turnDetectionSilenceDurationMs(properties.turnDetectionSilenceDurationMs());
        } else {
            // SDK 默认值会把 turn_detection 置为 server_vad，必须显式写 false 才会下发 null。
            builder.enableTurnDetection(false);
        }
        return builder.build();
    }

    /**
     * DashScope SDK 会话包装，统一转换受检异常为运行时异常。
     */
    private static final class SdkQwenRealtimeSession implements QwenRealtimeSession {
        /**
         * DashScope SDK 实时识别连接。
         */
        private final OmniRealtimeConversation conversation;

        /**
         * 当前会话配置。
         */
        private final OmniRealtimeConfig config;

        /**
         * 当前包装层是否已经执行过关闭，避免重复 close 触发 SDK 状态异常。
         */
        private boolean closed;

        /**
         * 创建 SDK 会话包装。
         */
        private SdkQwenRealtimeSession(OmniRealtimeConversation conversation, OmniRealtimeConfig config) {
            this.conversation = conversation;
            this.config = config;
        }

        @Override
        public void connect() {
            try {
                conversation.connect();
                conversation.updateSession(config);
            } catch (Exception ex) {
                throw new IllegalStateException("Qwen ASR Realtime 连接失败", ex);
            }
        }

        @Override
        public void appendAudio(String audioBase64) {
            conversation.appendAudio(audioBase64);
        }

        @Override
        public void commit() {
            conversation.commit();
        }

        @Override
        public void endSession() {
            try {
                conversation.endSession();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Qwen ASR Realtime 结束会话被中断", ex);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                conversation.close();
            } catch (RuntimeException ex) {
                if (isAlreadyClosed(ex)) {
                    return;
                }
                throw ex;
            }
        }

        /**
         * DashScope SDK 在服务端已经正常关闭连接后再次 close 会抛出该运行时异常，这里按幂等关闭处理。
         */
        private boolean isAlreadyClosed(RuntimeException ex) {
            return ex.getMessage() != null && ex.getMessage().contains("conversation is already closed");
        }
    }
}
