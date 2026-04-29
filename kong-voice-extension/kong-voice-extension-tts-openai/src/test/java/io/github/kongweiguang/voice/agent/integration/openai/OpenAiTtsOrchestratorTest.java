package io.github.kongweiguang.voice.agent.integration.openai;

import com.sun.net.httpserver.HttpServer;
import io.github.kongweiguang.voice.agent.extension.tts.openai.OpenAiTtsOrchestrator;
import io.github.kongweiguang.voice.agent.extension.tts.openai.OpenAiTtsProperties;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖默认 TTS 对 OpenAI Audio Speech 接口的适配行为。
 *
 * @author kongweiguang
 */
@Tag("audio")
@Tag("pipeline")
@DisplayName("OpenAI TTS 适配器")
class OpenAiTtsOrchestratorTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("调用 OpenAI TTS 接口并返回音频字节")
    void shouldSynthesizeWithOpenAiSpeechApi() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/audio/speech", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "WAV_BYTES".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OpenAiTtsOrchestrator orchestrator = newOrchestrator("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
        List<TtsChunk> chunks = orchestrator.synthesize("turn-1", 3, "你好", true);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().seq()).isEqualTo(3);
        assertThat(chunks.getFirst().last()).isTrue();
        assertThat(chunks.getFirst().audio()).isEqualTo("WAV_BYTES".getBytes(StandardCharsets.UTF_8));
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(requestBody.get())
                .contains("\"model\":\"gpt-4o-mini-tts\"")
                .contains("\"input\":\"你好\"")
                .contains("\"voice\":\"alloy\"")
                .contains("\"response_format\":\"wav\"");
    }

    @Test
    @DisplayName("短文本片段会累计到句子边界再合成")
    void shouldBufferShortTextUntilSentenceBoundary() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/audio/speech", exchange -> {
            requestCount.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "WAV_BYTES".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OpenAiTtsOrchestrator orchestrator = newOrchestrator("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
        List<TtsChunk> first = orchestrator.synthesize("turn-1", 0, "你好", false);
        List<TtsChunk> second = orchestrator.synthesize("turn-1", 0, "，世界。", false);

        assertThat(first).isEmpty();
        assertThat(second).hasSize(1);
        assertThat(second.getFirst().text()).isEqualTo("你好，世界。");
        assertThat(requestCount).hasValue(1);
        assertThat(requestBody.get()).contains("\"input\":\"你好，世界。\"");
    }

    @Test
    @DisplayName("非末尾文本未到句子边界时继续累计")
    void shouldBufferLongTextUntilSentenceBoundary() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/audio/speech", exchange -> {
            requestCount.incrementAndGet();
            byte[] response = "WAV_BYTES".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OpenAiTtsOrchestrator orchestrator = newOrchestrator("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
        List<TtsChunk> first = orchestrator.synthesize("turn-1", 0, "这是一段比较长但是还没有结束的文本", false);
        List<TtsChunk> second = orchestrator.synthesize("turn-1", 0, "。", false);

        assertThat(first).isEmpty();
        assertThat(second).hasSize(1);
        assertThat(second.getFirst().text()).isEqualTo("这是一段比较长但是还没有结束的文本。");
        assertThat(requestCount).hasValue(1);
    }

    @Test
    @DisplayName("流式入口复用单次 OpenAI 语音请求")
    void shouldReuseSingleSpeechRequestForStreamingMethod() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/audio/speech", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "AUDIO_1".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OpenAiTtsProperties properties = new OpenAiTtsProperties(
                "test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                "/audio/speech", "gpt-4o-mini-tts", "alloy", "wav", null, 1000);
        OpenAiTtsOrchestrator orchestrator = new OpenAiTtsOrchestrator(properties);
        List<TtsChunk> chunks = new ArrayList<>();
        orchestrator.synthesizeStreaming("turn-1", 5, "你好。", true, chunks::add);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).seq()).isEqualTo(5);
        assertThat(chunks.get(0).last()).isTrue();
        assertThat(chunks.get(0).audio()).isEqualTo("AUDIO_1".getBytes(StandardCharsets.UTF_8));
        assertThat(requestBody.get()).contains("\"input\":\"你好。\"");
    }

    @Test
    @DisplayName("纯标点片段不会调用 OpenAI TTS")
    void shouldSkipPunctuationOnlyText() {
        OpenAiTtsOrchestrator orchestrator = newOrchestrator("http://127.0.0.1:1", "test-key");

        List<TtsChunk> chunks = orchestrator.synthesize("turn-2", 0, "！？", true);

        assertThat(chunks).isEmpty();
    }

    @Test
    @DisplayName("OpenAI API Key 缺失时直接失败")
    void shouldFailWhenApiKeyMissing() {
        OpenAiTtsOrchestrator orchestrator = newOrchestrator("http://127.0.0.1:1", "");

        assertThatThrownBy(() -> orchestrator.synthesize("turn-2", 0, "测试", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpenAI API Key 未配置");
    }

    private OpenAiTtsOrchestrator newOrchestrator(String baseUrl, String apiKey) {
        OpenAiTtsProperties properties = new OpenAiTtsProperties(
                apiKey, baseUrl, "/audio/speech", "gpt-4o-mini-tts", "alloy", "wav", null, 1000);
        return new OpenAiTtsOrchestrator(properties);
    }
}
