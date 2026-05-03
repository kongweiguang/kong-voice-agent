package io.github.kongweiguang.voice.agent.integration.openai;
import com.sun.net.httpserver.HttpServer;
import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.extension.asr.openai.OpenAiAsrProperties;
import io.github.kongweiguang.voice.agent.extension.asr.openai.OpenAiStreamingAsrAdapter;
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
 * 覆盖默认 ASR 对 OpenAI Audio Transcriptions 接口的适配行为。
 *
 * @author kongweiguang
 */
@Tag("audio")
@Tag("protocol")
@DisplayName("OpenAI ASR 适配器")
class OpenAiStreamingAsrAdapterTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("commit 时调用 OpenAI 转写接口并返回识别文本")
    void shouldCommitWithOpenAiTranscript() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/audio/transcriptions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] response = "{\"text\":\"你好，世界\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OpenAiStreamingAsrAdapter adapter = newAdapter("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
        Optional<AsrUpdate> partial = adapter.acceptAudio("turn-1", new byte[AudioFormatSpec.DEFAULT.bytesForMs(40)]);
        AsrUpdate update = adapter.commitTurn("turn-1");

        assertThat(partial).isEmpty();
        assertThat(update.transcript()).isEqualTo("你好，世界");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(contentType.get()).contains("multipart/form-data");
        assertThat(requestBody.get())
                .contains("name=\"model\"")
                .contains("gpt-4o-mini-transcribe")
                .contains("name=\"response_format\"")
                .contains("json")
                .contains("name=\"language\"")
                .contains("zh")
                .contains("name=\"file\"; filename=\"audio.wav\"");
    }

    @Test
    @DisplayName("OpenAI API Key 缺失时直接失败")
    void shouldFailWhenApiKeyMissing() {
        OpenAiStreamingAsrAdapter adapter = newAdapter("http://127.0.0.1:1", "");
        adapter.acceptAudio("turn-2", new byte[AudioFormatSpec.DEFAULT.bytesForMs(20)]);

        assertThatThrownBy(() -> adapter.commitTurn("turn-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpenAI API Key 未配置");
    }

    private OpenAiStreamingAsrAdapter newAdapter(String baseUrl, String apiKey) {
        OpenAiAsrProperties properties = new OpenAiAsrProperties(
                apiKey, baseUrl, "/audio/transcriptions", "gpt-4o-mini-transcribe", "zh", 1000);
        return new OpenAiStreamingAsrAdapter(AudioFormatSpec.DEFAULT, properties);
    }
}
