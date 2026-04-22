package io.github.kongweiguang.voice.agent.integration.dashscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.integration.asr.DashScopeAsrProperties;
import io.github.kongweiguang.voice.agent.integration.asr.DashScopeStreamingAsrAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖默认 ASR 对 DashScope Qwen-ASR 的适配行为。
 *
 * @author kongweiguang
 */
@Tag("audio")
@Tag("protocol")
@DisplayName("DashScope Qwen-ASR 适配器")
class DashScopeStreamingAsrAdapterTest {
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
     * Qwen-ASR 开启时，commit 会把累计 PCM 包装成 WAV data URL 并读取兼容模式响应。
     */
    @Test
    @DisplayName("commit 时调用 DashScope 兼容接口并返回识别文本")
    void shouldCommitWithDashScopeTranscript() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"你好，世界\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        DashScopeStreamingAsrAdapter adapter = newAdapter("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
        Optional<AsrUpdate> partial = adapter.acceptAudio("turn-1", new byte[AudioFormatSpec.DEFAULT.bytesForMs(40)]);
        AsrUpdate update = adapter.commitTurn("turn-1");

        assertThat(partial).isEmpty();
        assertThat(update.transcript()).isEqualTo("你好，世界");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(requestBody.get())
                .contains("\"model\":\"qwen3-asr-flash\"")
                .contains("\"type\":\"input_audio\"")
                .contains("data:audio/wav;base64")
                .contains("\"enable_itn\":true")
                .contains("\"language\":\"zh\"");
    }

    /**
     * API Key 缺失时直接失败，避免发出不可鉴权请求。
     */
    @Test
    @DisplayName("DashScope API Key 缺失时直接失败")
    void shouldFailWhenApiKeyMissing() {
        DashScopeStreamingAsrAdapter adapter = newAdapter("http://127.0.0.1:1", "");
        adapter.acceptAudio("turn-2", new byte[AudioFormatSpec.DEFAULT.bytesForMs(20)]);

        assertThatThrownBy(() -> adapter.commitTurn("turn-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DashScope API Key 未配置");
    }

    /**
     * 创建带指定 DashScope 配置的测试适配器。
     */
    private DashScopeStreamingAsrAdapter newAdapter(String baseUrl, String apiKey) {
        DashScopeAsrProperties properties = new DashScopeAsrProperties(
                apiKey, baseUrl, "/chat/completions", "qwen3-asr-flash", "zh", true, 1000);
        return new DashScopeStreamingAsrAdapter(AudioFormatSpec.DEFAULT, properties, new ObjectMapper());
    }
}
