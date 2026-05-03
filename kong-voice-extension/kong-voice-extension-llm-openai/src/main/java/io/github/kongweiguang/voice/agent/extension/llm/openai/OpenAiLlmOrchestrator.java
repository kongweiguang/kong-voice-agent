package io.github.kongweiguang.voice.agent.extension.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.v1.http.client.Req;
import io.github.kongweiguang.v1.http.client.Res;
import io.github.kongweiguang.v1.http.client.consts.ContentType;
import io.github.kongweiguang.v1.http.client.consts.Method;
import io.github.kongweiguang.v1.http.client.spec.SseReqSpec;
import io.github.kongweiguang.v1.http.client.sse.SseEvent;
import io.github.kongweiguang.v1.http.client.sse.SseListener;
import io.github.kongweiguang.voice.agent.llm.LlmChunk;
import io.github.kongweiguang.voice.agent.llm.LlmOrchestrator;
import io.github.kongweiguang.voice.agent.llm.LlmRequest;
import io.github.kongweiguang.v1.json.Json;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
     * SSE 等待超时额外缓冲毫秒数，避免边界耗时误判。
     */
    private static final long SSE_TIMEOUT_BUFFER_MS = 1000L;

    @Override
    public void stream(LlmRequest request, Consumer<LlmChunk> chunkConsumer) {
        requireApiKey();
        try {
            streamSse(request, chunkConsumer);
        } catch (Exception ex) {
            throw new IllegalStateException("OpenAI LLM 调用失败，请检查 API Key、模型名称和网络连通性", ex);
        }
    }

    /**
     * 使用 kong-http SSE 客户端发起流式请求，并同步等待当前请求完成。
     */
    private void streamSse(LlmRequest request, Consumer<LlmChunk> chunkConsumer) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger nextSeq = new AtomicInteger();
        AtomicReference<String> pendingText = new AtomicReference<>();
        AtomicReference<String> pendingRawResponse = new AtomicReference<>();
        AtomicBoolean terminalEmitted = new AtomicBoolean();
        SseListener listener = new SseListener() {
            @Override
            public void event(SseReqSpec req, SseEvent msg) {
                handleSseData(msg.data(), request, chunkConsumer, nextSeq, pendingText, pendingRawResponse, terminalEmitted, done);
            }

            @Override
            public void fail(SseReqSpec req, Res res, Throwable t) {
                failure.set(t == null ? new IllegalStateException("OpenAI LLM SSE 连接失败") : t);
                done.countDown();
            }

            @Override
            public void closed(SseReqSpec req) {
                done.countDown();
            }
        };
        Req.sse(properties.baseUrl() + properties.chatCompletionsPath())
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .method(Method.POST)
                .bearer(properties.apiKey())
                .header("Accept", ContentType.EVENT_STREAM.value())
                .json(requestBody(request))
                .sseListener(listener)
                .ok();
        boolean completed = done.await(properties.timeoutMs() + SSE_TIMEOUT_BUFFER_MS, TimeUnit.MILLISECONDS);
        listener.closeCon();
        if (!completed) {
            throw new IllegalStateException("OpenAI LLM SSE 读取超时");
        }
        if (failure.get() != null) {
            throw new IllegalStateException("OpenAI LLM SSE 读取失败", failure.get());
        }
        if (terminalEmitted.get()) {
            return;
        }
        if (pendingText.get() != null) {
            chunkConsumer.accept(new LlmChunk(request.turnId(), nextSeq.get(), pendingText.get(), true, pendingRawResponse.get()));
            return;
        }
        chunkConsumer.accept(new LlmChunk(request.turnId(), nextSeq.get(), "", true, null));
    }

    /**
     * 按 SSE data 顺序输出文本，并确保最后一定给流水线补一个 last=true 结束标记。
     */
    private void handleSseData(String data, LlmRequest request, Consumer<LlmChunk> chunkConsumer, AtomicInteger nextSeq,
                               AtomicReference<String> pendingText, AtomicReference<String> pendingRawResponse,
                               AtomicBoolean terminalEmitted, CountDownLatch done) {
        if (data == null || data.isBlank()) {
            return;
        }
        String trimmedData = data.strip();
        if ("[DONE]".equals(trimmedData)) {
            done.countDown();
            return;
        }
        JsonNode root = Json.node(trimmedData);
        String content = extractContent(root);
        if (!content.isEmpty()) {
            if (pendingText.get() != null) {
                chunkConsumer.accept(new LlmChunk(request.turnId(), nextSeq.getAndIncrement(), pendingText.get(), false, pendingRawResponse.get()));
            }
            pendingText.set(content);
            pendingRawResponse.set(trimmedData);
        }
        if (hasFinishReason(root)) {
            if (pendingText.get() != null) {
                chunkConsumer.accept(new LlmChunk(request.turnId(), nextSeq.get(), pendingText.get(), true, pendingRawResponse.get()));
                pendingText.set(null);
                pendingRawResponse.set(null);
            } else {
                chunkConsumer.accept(new LlmChunk(request.turnId(), nextSeq.get(), "", true, trimmedData));
            }
            terminalEmitted.set(true);
            done.countDown();
        }
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
    private Map<String, Object> requestBody(LlmRequest request) {
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
        return requestBody;
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
}
