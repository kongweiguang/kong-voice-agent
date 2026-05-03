package io.github.kongweiguang.voice.agent.integration.qwen;

import com.google.gson.JsonObject;
import io.github.kongweiguang.voice.agent.extension.tts.qwen.QwenTtsOrchestrator;
import io.github.kongweiguang.voice.agent.extension.tts.qwen.QwenTtsProperties;
import io.github.kongweiguang.voice.agent.extension.tts.qwen.QwenTtsRealtimeSession;
import io.github.kongweiguang.voice.agent.extension.tts.qwen.QwenTtsRealtimeSessionFactory;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖默认 TTS 对 Qwen TTS Realtime 接口的适配行为。
 *
 * @author kongweiguang
 */
@Tag("audio")
@Tag("pipeline")
@DisplayName("Qwen TTS Realtime 适配器")
class QwenTtsOrchestratorTest {
    /**
     * 验证 realtime 音频增量会按序号转换为 TTS 音频块。
     */
    @Test
    @DisplayName("实时接口会解析 response.audio.delta 音频分片")
    void shouldSynthesizeWithQwenRealtimeApi() {
        FakeRealtimeSessionFactory sessionFactory = new FakeRealtimeSessionFactory();
        sessionFactory.audio("PCM_1", "PCM_2");
        QwenTtsOrchestrator orchestrator = newOrchestrator("server_commit", sessionFactory);
        List<TtsChunk> chunks = new ArrayList<>();

        orchestrator.synthesizeStreaming("turn-1", 5, "你好。", true, chunks::add);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).seq()).isEqualTo(5);
        assertThat(chunks.get(0).last()).isFalse();
        assertThat(chunks.get(0).audio()).isEqualTo("PCM_1".getBytes(StandardCharsets.UTF_8));
        assertThat(chunks.get(1).seq()).isEqualTo(6);
        assertThat(chunks.get(1).last()).isTrue();
        assertThat(chunks.get(1).audio()).isEqualTo("PCM_2".getBytes(StandardCharsets.UTF_8));
        assertThat(sessionFactory.lastSession.appendedText()).isEqualTo("你好。");
        assertThat(sessionFactory.lastSession.finishCount()).isEqualTo(1);
        assertThat(sessionFactory.lastSession.commitCount()).isZero();
        assertThat(sessionFactory.lastSession.closeCount()).isEqualTo(1);
    }

    /**
     * 验证 commit 模式会主动调用 SDK commit 触发合成。
     */
    @Test
    @DisplayName("commit 模式会主动提交文本缓冲区")
    void shouldCommitWhenConfiguredWithCommitMode() {
        FakeRealtimeSessionFactory sessionFactory = new FakeRealtimeSessionFactory();
        sessionFactory.audio("PCM_1");
        QwenTtsOrchestrator orchestrator = newOrchestrator("commit", sessionFactory);

        List<TtsChunk> chunks = orchestrator.synthesize("turn-1", 0, "你好。", true);

        assertThat(chunks).hasSize(1);
        assertThat(sessionFactory.lastSession.commitCount()).isEqualTo(1);
        assertThat(sessionFactory.lastSession.finishCount()).isZero();
    }

    /**
     * 验证短文本片段不会在句子边界前过早触发外部 TTS 调用。
     */
    @Test
    @DisplayName("短文本片段会累计到句子边界再合成")
    void shouldBufferShortTextUntilSentenceBoundary() {
        FakeRealtimeSessionFactory sessionFactory = new FakeRealtimeSessionFactory();
        sessionFactory.audio("PCM_1");
        QwenTtsOrchestrator orchestrator = newOrchestrator("server_commit", sessionFactory);

        List<TtsChunk> first = orchestrator.synthesize("turn-1", 0, "你好", false);
        List<TtsChunk> second = orchestrator.synthesize("turn-1", 0, "，世界。", false);

        assertThat(first).isEmpty();
        assertThat(second).hasSize(1);
        assertThat(second.getFirst().text()).isEqualTo("你好，世界。");
        assertThat(sessionFactory.createCount).hasValue(1);
        assertThat(sessionFactory.lastSession.appendedText()).isEqualTo("你好，世界。");
    }

    /**
     * 验证缺少 API Key 时直接失败，避免误访问外部服务。
     */
    @Test
    @DisplayName("Qwen API Key 缺失时直接失败")
    void shouldFailWhenApiKeyMissing() {
        QwenTtsProperties properties = new QwenTtsProperties(
                "", "wss://example.invalid", "qwen3-tts-flash-realtime", "Cherry", "Chinese",
                "server_commit", "PCM_24000HZ_MONO_16BIT", null, null,
                null, null, null, null, null, null, 1000);
        QwenTtsOrchestrator orchestrator = new QwenTtsOrchestrator(properties, new FakeRealtimeSessionFactory());

        assertThatThrownBy(() -> orchestrator.synthesize("turn-2", 0, "测试", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Qwen TTS API Key 未配置");
    }

    /**
     * 创建使用假 realtime 会话的 Qwen TTS 编排器。
     */
    private QwenTtsOrchestrator newOrchestrator(String mode, FakeRealtimeSessionFactory sessionFactory) {
        QwenTtsProperties properties = new QwenTtsProperties(
                "test-key", "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
                "qwen3-tts-flash-realtime", "Cherry", "Chinese",
                mode, "PCM_24000HZ_MONO_16BIT", null, null,
                null, null, null, null, null, null, 1000);
        return new QwenTtsOrchestrator(properties, sessionFactory);
    }

    /**
     * 可控的 fake realtime 会话工厂，用于避免测试访问真实 DashScope WebSocket。
     */
    private static final class FakeRealtimeSessionFactory implements QwenTtsRealtimeSessionFactory {
        /**
         * 创建会话次数。
         */
        private final AtomicInteger createCount = new AtomicInteger();

        /**
         * 最近一次创建的 fake 会话。
         */
        private FakeRealtimeSession lastSession;

        /**
         * 待模拟输出的音频片段。
         */
        private List<String> audioChunks = List.of();

        /**
         * 配置 fake 会话输出的原始音频片段。
         */
        private void audio(String... chunks) {
            audioChunks = List.of(chunks);
        }

        @Override
        public QwenTtsRealtimeSession create(QwenTtsProperties properties, Consumer<JsonObject> eventConsumer) {
            createCount.incrementAndGet();
            lastSession = new FakeRealtimeSession(eventConsumer, audioChunks);
            return lastSession;
        }
    }

    /**
     * 模拟 DashScope Qwen TTS Realtime 会话。
     */
    private static final class FakeRealtimeSession implements QwenTtsRealtimeSession {
        /**
         * 事件消费者。
         */
        private final Consumer<JsonObject> eventConsumer;

        /**
         * 待输出音频片段。
         */
        private final List<String> audioChunks;

        /**
         * 已追加文本。
         */
        private final AtomicReference<String> appendedText = new AtomicReference<>("");

        /**
         * connect 调用次数。
         */
        private final AtomicInteger connectCount = new AtomicInteger();

        /**
         * commit 调用次数。
         */
        private final AtomicInteger commitCount = new AtomicInteger();

        /**
         * finish 调用次数。
         */
        private final AtomicInteger finishCount = new AtomicInteger();

        /**
         * close 调用次数。
         */
        private final AtomicInteger closeCount = new AtomicInteger();

        /**
         * 创建 fake realtime 会话。
         */
        private FakeRealtimeSession(Consumer<JsonObject> eventConsumer, List<String> audioChunks) {
            this.eventConsumer = eventConsumer;
            this.audioChunks = audioChunks;
        }

        @Override
        public void connect() {
            connectCount.incrementAndGet();
            JsonObject created = new JsonObject();
            created.addProperty("type", "session.created");
            eventConsumer.accept(created);
        }

        @Override
        public void appendText(String text) {
            appendedText.updateAndGet(current -> current + text);
        }

        @Override
        public void commit() {
            commitCount.incrementAndGet();
            emitAudioAndDone("response.done");
        }

        @Override
        public void finish() {
            finishCount.incrementAndGet();
            emitAudioAndDone("session.finished");
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        /**
         * 模拟服务端输出音频增量和结束事件。
         */
        private void emitAudioAndDone(String doneType) {
            for (String audioChunk : audioChunks) {
                JsonObject audio = new JsonObject();
                audio.addProperty("type", "response.audio.delta");
                audio.addProperty("delta", Base64.getEncoder().encodeToString(audioChunk.getBytes(StandardCharsets.UTF_8)));
                eventConsumer.accept(audio);
            }
            JsonObject done = new JsonObject();
            done.addProperty("type", doneType);
            eventConsumer.accept(done);
        }

        private String appendedText() {
            return appendedText.get();
        }

        private int commitCount() {
            return commitCount.get();
        }

        private int finishCount() {
            return finishCount.get();
        }

        private int closeCount() {
            return closeCount.get();
        }
    }
}
