package io.github.kongweiguang.voice.agent.extension.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.voice.agent.llm.LlmChunk;
import io.github.kongweiguang.voice.agent.llm.LlmOrchestrator;
import io.github.kongweiguang.voice.agent.llm.LlmRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 基于 OpenAI Chat Completions SSE 输出的 LLM 编排器。
 *
 * @author kongweiguang
 */
@RequiredArgsConstructor
public class OpenAiLlmOrchestrator implements LlmOrchestrator {
    /**
     * OpenAI LLM 服务配置。
     */
    private final OpenAiLlmProperties properties;

    /**
     * JSON 解析器，用于读取 SSE chunk 并保留原始响应。
     */
    private final ObjectMapper objectMapper;

    @Override
    public void stream(LlmRequest request, Consumer<LlmChunk> chunkConsumer) {
        requireApiKey();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl() + properties.chatCompletionsPath()))
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(writeRequestBody(request), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = httpClient().send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("OpenAI LLM 调用失败，HTTP 状态码: " + response.statusCode()
                        + "，响应体: " + readBody(response.body()));
            }
            readSse(response.body(), request, chunkConsumer);
        } catch (Exception ex) {
            throw new IllegalStateException("OpenAI LLM 调用失败，请检查 API Key、模型名称和网络连通性", ex);
        }
    }

    /**
     * 按 SSE chunk 顺序输出文本，并确保最后一定给流水线补一个 last=true 结束标记。
     */
    private void readSse(InputStream bodyStream, LlmRequest request, Consumer<LlmChunk> chunkConsumer) throws Exception {
        Integer nextSeq = 0;
        String pendingText = null;
        String pendingRawResponse = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(bodyStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || !line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).strip();
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode root = objectMapper.readTree(data);
                String content = extractContent(root);
                if (!content.isEmpty()) {
                    if (pendingText != null) {
                        chunkConsumer.accept(new LlmChunk(request.turnId(), nextSeq, pendingText, false, pendingRawResponse));
                        nextSeq++;
                    }
                    pendingText = content;
                    pendingRawResponse = data;
                }
                if (hasFinishReason(root)) {
                    if (pendingText != null) {
                        chunkConsumer.accept(new LlmChunk(request.turnId(), nextSeq, pendingText, true, pendingRawResponse));
                    } else {
                        chunkConsumer.accept(new LlmChunk(request.turnId(), nextSeq, "", true, data));
                    }
                    return;
                }
            }
        }
        if (pendingText != null) {
            chunkConsumer.accept(new LlmChunk(request.turnId(), nextSeq, pendingText, true, pendingRawResponse));
            return;
        }
        chunkConsumer.accept(new LlmChunk(request.turnId(), nextSeq, "", true, null));
    }

    /**
     * 从 OpenAI 兼容 chunk 中提取可直接进入 TTS 的文本增量。
     */
    private String extractContent(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode choice : choices) {
            appendContent(builder, choice.path("delta").path("content"));
            appendContent(builder, choice.path("message").path("content"));
        }
        return builder.toString();
    }

    private void appendContent(StringBuilder builder, JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return;
        }
        if (contentNode.isTextual()) {
            builder.append(contentNode.asText());
            return;
        }
        if (contentNode.isArray()) {
            for (JsonNode item : contentNode) {
                if (item.isTextual()) {
                    builder.append(item.asText());
                    continue;
                }
                JsonNode textNode = item.path("text");
                if (textNode.isTextual()) {
                    builder.append(textNode.asText());
                }
            }
        }
    }

    private boolean hasFinishReason(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray()) {
            return false;
        }
        for (JsonNode choice : choices) {
            JsonNode finishReason = choice.path("finish_reason");
            if (!finishReason.isMissingNode() && !finishReason.isNull()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构造 OpenAI Chat Completions 请求体。
     */
    private String writeRequestBody(LlmRequest request) {
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", properties.model());
            requestBody.put("stream", true);
            if (properties.temperature() != null) {
                requestBody.put("temperature", properties.temperature());
            }
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(message("system", properties.systemPrompt()));
            messages.add(message("user", request.finalTranscript()));
            requestBody.put("messages", messages);
            return objectMapper.writeValueAsString(requestBody);
        } catch (Exception ex) {
            throw new IllegalStateException("OpenAI LLM 请求体序列化失败", ex);
        }
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private void requireApiKey() {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("OpenAI API Key 未配置，请设置 OPENAI_API_KEY 或 KONG_VOICE_AGENT_OPENAI_API_KEY");
        }
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.timeoutMs()))
                .build();
    }

    private String readBody(InputStream body) {
        try (InputStream input = body) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "<unavailable>";
        }
    }
}
