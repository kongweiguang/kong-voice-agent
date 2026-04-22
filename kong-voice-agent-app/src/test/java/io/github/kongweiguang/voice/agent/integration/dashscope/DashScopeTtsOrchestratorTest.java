package io.github.kongweiguang.voice.agent.integration.dashscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.kongweiguang.voice.agent.integration.tts.DashScopeTtsOrchestrator;
import io.github.kongweiguang.voice.agent.integration.tts.DashScopeTtsProperties;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖默认 TTS 对 DashScope Qwen-TTS 的适配行为。
 *
 * @author kongweiguang
 */
@Tag("audio")
@Tag("pipeline")
@DisplayName("DashScope Qwen-TTS 适配器")
class DashScopeTtsOrchestratorTest {
    /**
     * 测试期间启动的轻量 HTTP 服务。
     */
    private HttpServer server;

    /**
     * 每个测试结束后关闭本地 HTTP 服务，避免端口和线程泄漏。
     */
    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 服务开启时，TTS 会调用 DashScope generation 路径并解码响应中的 base64 音频。
     */
    @Test
    @DisplayName("调用 DashScope TTS 接口并返回音频字节")
    void shouldSynthesizeWithDashScopeTtsApi() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/aigc/multimodal-generation/generation", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String audioBase64 = Base64.getEncoder().encodeToString("WAV_BYTES".getBytes(StandardCharsets.UTF_8));
            byte[] response = ("{\"output\":{\"audio\":{\"data\":\"" + audioBase64 + "\"}}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        DashScopeTtsOrchestrator orchestrator = newOrchestrator("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
        List<TtsChunk> chunks = orchestrator.synthesize("turn-1", 3, "你好", true);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().seq()).isEqualTo(3);
        assertThat(chunks.getFirst().last()).isTrue();
        assertThat(chunks.getFirst().audio()).isEqualTo("WAV_BYTES".getBytes(StandardCharsets.UTF_8));
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(requestBody.get())
                .contains("\"model\":\"qwen3-tts-flash\"")
                .contains("\"text\":\"你好\"")
                .contains("\"voice\":\"Cherry\"")
                .contains("\"language_type\":\"Chinese\"");
    }

    /**
     * DashScope 也可能只返回 OSS 临时 URL，下载时必须保留原始签名 query。
     */
    @Test
    @DisplayName("使用原始 OSS 签名 URL 下载音频")
    void shouldDownloadAudioWithOriginalSignedUrl() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/aigc/multimodal-generation/generation", exchange -> {
            String audioUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/signed.wav?OSSAccessKeyId=test&Signature=fO1%2F6yl%2FESCPDFEIWCSb5u9hzAE%3D&Expires=1776911523";
            byte[] response = ("{\"output\":{\"audio\":{\"url\":\"" + audioUrl + "\"}}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/signed.wav", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] response = "SIGNED_WAV_BYTES".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        DashScopeTtsOrchestrator orchestrator = newOrchestrator("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
        List<TtsChunk> chunks = orchestrator.synthesize("turn-1", 0, "你好，世界。", true);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().audio()).isEqualTo("SIGNED_WAV_BYTES".getBytes(StandardCharsets.UTF_8));
        assertThat(rawQuery.get()).contains("Signature=fO1%2F6yl%2FESCPDFEIWCSb5u9hzAE%3D");
    }

    /**
     * 流式 LLM 输出短片段时，Qwen-TTS 适配器会累计到句子边界再合成，避免单 token 触发 invalid text。
     */
    @Test
    @DisplayName("短文本片段会累计到句子边界再合成")
    void shouldBufferShortTextUntilSentenceBoundary() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/aigc/multimodal-generation/generation", exchange -> {
            requestCount.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String audioBase64 = Base64.getEncoder().encodeToString("WAV_BYTES".getBytes(StandardCharsets.UTF_8));
            byte[] response = ("{\"output\":{\"audio\":{\"data\":\"" + audioBase64 + "\"}}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        DashScopeTtsOrchestrator orchestrator = newOrchestrator("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
        List<TtsChunk> first = orchestrator.synthesize("turn-1", 0, "你好", false);
        List<TtsChunk> second = orchestrator.synthesize("turn-1", 0, "，世界。", false);

        assertThat(first).isEmpty();
        assertThat(second).hasSize(1);
        assertThat(second.getFirst().text()).isEqualTo("你好，世界。");
        assertThat(requestCount).hasValue(1);
        assertThat(requestBody.get()).contains("\"text\":\"你好，世界。\"");
    }

    /**
     * 非流式模式下，未到句末的长文本也继续累计，避免按长度切出不自然的音频碎片。
     */
    @Test
    @DisplayName("非末尾文本未到句子边界时继续累计")
    void shouldBufferLongTextUntilSentenceBoundary() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/aigc/multimodal-generation/generation", exchange -> {
            requestCount.incrementAndGet();
            String audioBase64 = Base64.getEncoder().encodeToString("WAV_BYTES".getBytes(StandardCharsets.UTF_8));
            byte[] response = ("{\"output\":{\"audio\":{\"data\":\"" + audioBase64 + "\"}}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        DashScopeTtsOrchestrator orchestrator = newOrchestrator("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
        List<TtsChunk> first = orchestrator.synthesize("turn-1", 0, "这是一段比较长但是还没有结束的文本", false);
        List<TtsChunk> second = orchestrator.synthesize("turn-1", 0, "。", false);

        assertThat(first).isEmpty();
        assertThat(second).hasSize(1);
        assertThat(second.getFirst().text()).isEqualTo("这是一段比较长但是还没有结束的文本。");
        assertThat(requestCount).hasValue(1);
    }

    /**
     * DashScope SSE 流式模式会按远端 data 事件持续回调音频块，并只把最后一块标记为 last。
     */
    @Test
    @DisplayName("DashScope SSE 流式 TTS 会连续回调音频块")
    void shouldStreamDashScopeTtsAudioChunks() throws Exception {
        AtomicReference<String> sseHeader = new AtomicReference<>();
        AtomicInteger completeAudioDownloadCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/aigc/multimodal-generation/generation", exchange -> {
            sseHeader.set(exchange.getRequestHeaders().getFirst("X-DashScope-SSE"));
            String first = Base64.getEncoder().encodeToString("AUDIO_1".getBytes(StandardCharsets.UTF_8));
            String second = Base64.getEncoder().encodeToString("AUDIO_2".getBytes(StandardCharsets.UTF_8));
            String completeAudioUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/complete.wav";
            byte[] response = ("data: {\"output\":{\"audio\":{\"data\":\"" + first + "\"}}}\n\n"
                    + "data: {\"output\":{\"audio\":{\"data\":\"" + second + "\"}}}\n\n"
                    + "data: {\"output\":{\"audio\":{\"url\":\"" + completeAudioUrl + "\"}}}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/complete.wav", exchange -> {
            completeAudioDownloadCount.incrementAndGet();
            byte[] response = "COMPLETE_AUDIO".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        DashScopeTtsProperties properties = new DashScopeTtsProperties(
                "test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                "/api/v1/services/aigc/multimodal-generation/generation", "qwen3-tts-flash", "Cherry", "Chinese", true, 1000);
        DashScopeTtsOrchestrator orchestrator = new DashScopeTtsOrchestrator(properties, new ObjectMapper());
        List<TtsChunk> chunks = new ArrayList<>();
        orchestrator.synthesizeStreaming("turn-1", 5, "你好。", true, chunks::add);

        assertThat(sseHeader.get()).isEqualTo("enable");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).seq()).isEqualTo(5);
        assertThat(chunks.get(0).last()).isFalse();
        assertThat(chunks.get(0).audio()).isEqualTo("AUDIO_1".getBytes(StandardCharsets.UTF_8));
        assertThat(chunks.get(1).seq()).isEqualTo(6);
        assertThat(chunks.get(1).last()).isTrue();
        assertThat(chunks.get(1).audio()).isEqualTo("AUDIO_2".getBytes(StandardCharsets.UTF_8));
        assertThat(completeAudioDownloadCount).hasValue(0);
    }

    /**
     * 纯标点或空白片段没有可播报字符，最后一个 chunk 也不会调用远端 TTS。
     */
    @Test
    @DisplayName("纯标点片段不会调用 DashScope TTS")
    void shouldSkipPunctuationOnlyText() {
        DashScopeTtsOrchestrator orchestrator = newOrchestrator("http://127.0.0.1:1", "test-key");

        List<TtsChunk> chunks = orchestrator.synthesize("turn-2", 0, "！？", true);

        assertThat(chunks).isEmpty();
    }

    /**
     * API Key 缺失时直接失败，避免发出不可鉴权请求。
     */
    @Test
    @DisplayName("DashScope API Key 缺失时直接失败")
    void shouldFailWhenApiKeyMissing() {
        DashScopeTtsOrchestrator orchestrator = newOrchestrator("http://127.0.0.1:1", "");

        assertThatThrownBy(() -> orchestrator.synthesize("turn-2", 0, "测试", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DashScope API Key 未配置");
    }

    /**
     * 创建带指定 DashScope 配置的测试编排器。
     */
    private DashScopeTtsOrchestrator newOrchestrator(String baseUrl, String apiKey) {
        DashScopeTtsProperties properties = new DashScopeTtsProperties(
                apiKey, baseUrl, "/api/v1/services/aigc/multimodal-generation/generation", "qwen3-tts-flash", "Cherry", "Chinese", false, 1000);
        return new DashScopeTtsOrchestrator(properties, new ObjectMapper());
    }
}
