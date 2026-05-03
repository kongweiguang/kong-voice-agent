package io.github.kongweiguang.voice.agent.integration.openai;
import com.sun.net.httpserver.HttpServer;
import io.github.kongweiguang.voice.agent.extension.llm.openai.OpenAiLlmOrchestrator;
import io.github.kongweiguang.voice.agent.extension.llm.openai.OpenAiLlmProperties;
import io.github.kongweiguang.voice.agent.llm.LlmChunk;
import io.github.kongweiguang.voice.agent.llm.LlmRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖 OpenAI LLM 扩展的请求构造和 SSE 解析行为。
 *
 * @author kongweiguang
 */
@Tag("pipeline")
@Tag("protocol")
@DisplayName("OpenAI LLM 适配器")
class OpenAiLlmOrchestratorTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("调用 OpenAI Chat Completions SSE 并输出流式文本片段")
    void shouldStreamTextChunksFromOpenAiChatCompletions() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = String.join("\n\n",
                    "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}",
                    "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"},\"finish_reason\":null}]}",
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                    "data: [DONE]",
                    "").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OpenAiLlmOrchestrator orchestrator = newOrchestrator("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
        List<LlmChunk> chunks = new ArrayList<>();

        orchestrator.stream(new LlmRequest("session-1", "turn-1", "你好"), chunks::add);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).turnId()).isEqualTo("turn-1");
        assertThat(chunks.get(0).seq()).isEqualTo(0);
        assertThat(chunks.get(0).text()).isEqualTo("你好");
        assertThat(chunks.get(0).last()).isFalse();
        assertThat(chunks.get(1).seq()).isEqualTo(1);
        assertThat(chunks.get(1).text()).isEqualTo("，世界");
        assertThat(chunks.get(1).last()).isTrue();
        assertThat(chunks.get(1).rawResponse()).contains("\"finish_reason\":null");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(requestBody.get())
                .contains("\"model\":\"gpt-4o-mini\"")
                .contains("\"stream\":true")
                .contains("\"role\":\"system\"")
                .contains("\"role\":\"user\"");
    }

    @Test
    @DisplayName("只有结束事件时也会补发 last=true 片段关闭本轮 LLM")
    void shouldEmitTerminalChunkWhenNoTextReturned() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] response = String.join("\n\n",
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                    "data: [DONE]",
                    "").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OpenAiLlmOrchestrator orchestrator = newOrchestrator("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
        List<LlmChunk> chunks = new ArrayList<>();

        orchestrator.stream(new LlmRequest("session-1", "turn-1", "你好"), chunks::add);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().text()).isEmpty();
        assertThat(chunks.getFirst().last()).isTrue();
    }

    @Test
    @DisplayName("OpenAI API Key 缺失时直接失败")
    void shouldFailWhenApiKeyMissing() {
        OpenAiLlmOrchestrator orchestrator = newOrchestrator("http://127.0.0.1:1", "");

        assertThatThrownBy(() -> orchestrator.stream(new LlmRequest("session-1", "turn-1", "测试"), ignored -> {
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpenAI API Key 未配置");
    }

    private OpenAiLlmOrchestrator newOrchestrator(String baseUrl, String apiKey) {
        OpenAiLlmProperties properties = new OpenAiLlmProperties(
                apiKey, baseUrl, "/chat/completions", "gpt-4o-mini", "请用中文回复", null, 1000);
        return new OpenAiLlmOrchestrator(properties);
    }
}
