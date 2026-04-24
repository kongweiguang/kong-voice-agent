package io.github.kongweiguang.voice.agent.integration.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;
import io.github.kongweiguang.voice.agent.tts.TtsOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 基于 DashScope Qwen-TTS 的 TTS 编排器。
 *
 * @author kongweiguang
 */
@RequiredArgsConstructor
public class DashScopeTtsOrchestrator implements TtsOrchestrator {
    /**
     * DashScope 开启 SSE 流式响应的请求头名称。
     */
    private static final String DASH_SCOPE_SSE_HEADER = "X-DashScope-SSE";

    /**
     * DashScope 开启 SSE 流式响应的请求头取值。
     */
    private static final String DASH_SCOPE_SSE_ENABLE = "enable";

    /**
     * 每个 turn 独立累计待合成文本，保证异步 turnId 之间不会串音。
     */
    private final ConcurrentMap<String, String> pendingTextByTurn = new ConcurrentHashMap<>();

    /**
     * DashScope Qwen-TTS 服务配置。
     */
    private final DashScopeTtsProperties properties;

    /**
     * JSON 解析器，用于读取 DashScope multimodal-generation 响应。
     */
    private final ObjectMapper objectMapper;

    /**
     * 将当前 LLM 文本片段提交给 Qwen-TTS 服务，并返回对应音频块。
     */
    @Override
    public List<TtsChunk> synthesize(String turnId, Integer startSeq, String text, Boolean lastTextChunk) {
        String pendingText = takeReadyText(turnId, text, lastTextChunk);
        if (pendingText.isBlank()) {
            // 文本尚未到句子边界或不可播报时不产出音频，流水线会继续等待后续 LLM 片段。
            return List.of();
        }
        return List.of(new TtsChunk(turnId, startSeq, lastTextChunk, speechAudio(pendingText), pendingText));
    }

    /**
     * 按句聚合 LLM 文本，并在 DashScope 流式模式开启时边读取 SSE 音频边回调下游。
     */
    @Override
    public void synthesizeStreaming(String turnId, Integer startSeq, String text, Boolean lastTextChunk,
                                    Consumer<TtsChunk> chunkConsumer) {
        String pendingText = takeReadyText(turnId, text, lastTextChunk);
        if (pendingText.isBlank()) {
            return;
        }
        if (!properties.streamingEnabled()) {
            // 关闭远端流式模式时，一句文本只产出一个音频块。
            chunkConsumer.accept(new TtsChunk(turnId, startSeq, lastTextChunk, speechAudio(pendingText), pendingText));
            return;
        }
        speechAudioStreaming(turnId, startSeq, pendingText, lastTextChunk, chunkConsumer);
    }

    /**
     * 释放指定 turn 尚未达到句子边界的待合成文本。
     */
    @Override
    public void cancelTurn(String turnId) {
        if (turnId != null) {
            pendingTextByTurn.remove(turnId);
        }
    }

    /**
     * 调用 DashScope Qwen-TTS 接口，失败时直接抛出异常，交由流水线转换为 error 事件。
     */
    private byte[] speechAudio(String text) {
        requireApiKey();
        try {
            String response = restClient().post()
                    .uri(properties.generationPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .body(requestBody(text))
                    .retrieve()
                    .body(String.class);
            byte[] audio = readAudio(response);
            if (audio.length == 0) {
                throw new IllegalStateException("DashScope Qwen-TTS 返回了空音频");
            }
            return audio;
        } catch (Exception ex) {
            throw new IllegalStateException("DashScope Qwen-TTS 调用失败，请检查 API Key、模型名称和网络连通性", ex);
        }
    }

    /**
     * 调用 DashScope SSE 流式 TTS 接口，读取到一个音频分片就立即交给流水线下发。
     */
    private void speechAudioStreaming(String turnId, Integer startSeq, String text, Boolean lastTextChunk,
                                      Consumer<TtsChunk> chunkConsumer) {
        requireApiKey();
        try {
            // DashScope SSE 返回的每段音频会立即转成 TtsChunk，随后由 VoicePipelineService 下发。
            restClient().post()
                    .uri(properties.generationPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .header(DASH_SCOPE_SSE_HEADER, DASH_SCOPE_SSE_ENABLE)
                    .body(requestBody(text))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            throw new IllegalStateException("DashScope Qwen-TTS 流式接口返回异常: "
                                    + response.getStatusCode() + " " + body);
                        }
                        readStreamingAudio(turnId, startSeq, text, lastTextChunk, response.getBody(), chunkConsumer);
                        return null;
                    });
        } catch (Exception ex) {
            throw new IllegalStateException("DashScope Qwen-TTS 流式调用失败，请检查 API Key、模型名称和网络连通性", ex);
        }
    }

    /**
     * 构造 DashScope multimodal-generation 请求体。
     */
    private Map<String, Object> requestBody(String text) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", text);
        input.put("voice", properties.voice());
        input.put("language_type", properties.languageType());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.model());
        request.put("input", input);
        return request;
    }

    /**
     * 追加当前 turn 的可播报文本，空白或纯控制字符不会进入远端 TTS。
     */
    private String appendPendingText(String turnId, String text) {
        if (text.isBlank()) {
            return pendingTextByTurn.getOrDefault(turnId, "");
        }
        return pendingTextByTurn.merge(turnId, text, String::concat);
    }

    /**
     * 取出已满足合成条件的文本；非末尾片段必须累计到句子边界，避免短音频造成播放断续。
     */
    private String takeReadyText(String turnId, String text, Boolean lastTextChunk) {
        String pendingText = appendPendingText(turnId, normalizeText(text));
        if (pendingText.isBlank()) {
            return "";
        }
        if (!hasSpeakableCharacter(pendingText)) {
            if (Boolean.TRUE.equals(lastTextChunk)) {
                pendingTextByTurn.remove(turnId);
            }
            return "";
        }
        if (!Boolean.TRUE.equals(lastTextChunk) && !endsWithSentencePunctuation(pendingText.strip())) {
            // 非末尾 LLM 片段需要等到自然句末，避免前端播放很多过短音频。
            return "";
        }
        pendingTextByTurn.remove(turnId);
        return pendingText;
    }

    /**
     * 检查是否遇到自然句子边界，便于在流式 LLM 输出时按句合成。
     */
    private boolean endsWithSentencePunctuation(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char last = text.charAt(text.length() - 1);
        return last == '。' || last == '！' || last == '？' || last == '；'
                || last == '.' || last == '!' || last == '?' || last == ';';
    }

    /**
     * 清洗 LLM 片段中不适合直接送入 Qwen-TTS 的内容，保留可朗读文本和常见标点。
     */
    private String normalizeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String withoutThinkingTags = text
                .replace("<think>", "")
                .replace("</think>", "")
                .replace("<thinking>", "")
                .replace("</thinking>", "");
        StringBuilder normalized = new StringBuilder();
        withoutThinkingTags.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .forEach(normalized::appendCodePoint);
        return normalized.toString().replaceAll("\\s+", " ").strip();
    }

    /**
     * 至少包含一个字母、数字或 CJK 字符才视为可播报，纯标点片段会等待后续正文。
     */
    private boolean hasSpeakableCharacter(String text) {
        return text.codePoints().anyMatch(codePoint ->
                Character.isLetterOrDigit(codePoint)
                        || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    /**
     * 从 DashScope 响应中读取音频；优先使用 base64 data 字段，没有时下载 audio.url。
     */
    private byte[] readAudio(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("DashScope Qwen-TTS 返回了空响应");
        }
        byte[] audio = readAudioOrUrlIfPresent(response);
        if (audio.length > 0) {
            return audio;
        }
        throw new IllegalStateException("DashScope Qwen-TTS 响应中缺少 output.audio.data 或 output.audio.url");
    }

    /**
     * 从非流式 JSON 响应中读取音频，data 缺失时允许下载最终 audio.url。
     */
    private byte[] readAudioOrUrlIfPresent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode audio = root.path("output").path("audio");
            JsonNode data = audio.path("data");
            if (data.isTextual() && !data.asText().isBlank()) {
                return Base64.getDecoder().decode(data.asText());
            }
            JsonNode url = audio.path("url");
            if (url.isTextual() && !url.asText().isBlank()) {
                return downloadAudio(url.asText());
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("DashScope Qwen-TTS 返回的 base64 音频无法解码", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("DashScope Qwen-TTS 响应格式不符合预期", ex);
        }
        return new byte[0];
    }

    /**
     * 从 SSE data 中只读取增量音频 data；流式末包里的完整 audio.url 不能再次下载，否则会重复播放整句。
     */
    private byte[] readStreamingAudioDataIfPresent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("output").path("audio").path("data");
            if (data.isTextual() && !data.asText().isBlank()) {
                return Base64.getDecoder().decode(data.asText());
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("DashScope Qwen-TTS 返回的流式 base64 音频无法解码", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("DashScope Qwen-TTS 流式响应格式不符合预期", ex);
        }
        return new byte[0];
    }

    /**
     * 解析 DashScope SSE 响应，每个 data 段可能携带一段 base64 音频。
     */
    private void readStreamingAudio(String turnId, Integer startSeq, String text, Boolean lastTextChunk,
                                    InputStream body, Consumer<TtsChunk> chunkConsumer) {
        AtomicInteger nextSeq = new AtomicInteger(startSeq == null ? 0 : startSeq);
        AtomicReference<TtsChunk> previous = new AtomicReference<>();
        AtomicBoolean receivedAudio = new AtomicBoolean(false);
        StringBuilder data = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    // SSE 空行表示一个 data 事件结束，此时尝试解析并发出上一段音频。
                    emitStreamingData(turnId, text, data.toString(), nextSeq, previous, receivedAudio, chunkConsumer);
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).strip());
                }
            }
            emitStreamingData(turnId, text, data.toString(), nextSeq, previous, receivedAudio, chunkConsumer);
            TtsChunk lastChunk = previous.get();
            if (lastChunk != null) {
                // 最后一块必须延后到读取结束后标记 last=true，前端才能正确关闭播放状态。
                chunkConsumer.accept(new TtsChunk(lastChunk.turnId(), lastChunk.seq(), lastTextChunk, lastChunk.audio(), lastChunk.text()));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("DashScope Qwen-TTS 流式响应解析失败", ex);
        }
        if (!receivedAudio.get()) {
            throw new IllegalStateException("DashScope Qwen-TTS 流式响应没有返回音频");
        }
    }

    /**
     * 将 SSE data 中的音频转成 TTS chunk；保留前一个 chunk，确保最后一块才能携带 last=true。
     */
    private void emitStreamingData(String turnId, String text, String data, AtomicInteger nextSeq,
                                   AtomicReference<TtsChunk> previous, AtomicBoolean receivedAudio,
                                   Consumer<TtsChunk> chunkConsumer) {
        if (data == null || data.isBlank() || "[DONE]".equals(data)) {
            return;
        }
        byte[] audio = readStreamingAudioDataIfPresent(data);
        if (audio.length == 0) {
            return;
        }
        receivedAudio.set(true);
        TtsChunk current = new TtsChunk(turnId, nextSeq.getAndIncrement(), false, audio, text);
        TtsChunk ready = previous.getAndSet(current);
        if (ready != null) {
            // 延迟一个 chunk 下发，确保还不知道是否为末尾的音频不会错误携带 last=true。
            chunkConsumer.accept(ready);
        }
    }

    /**
     * 下载 DashScope 响应中给出的音频 URL。
     */
    private byte[] downloadAudio(String url) {
        byte[] audio = RestClient.create()
                .get()
                // OSS 临时 URL 的签名依赖原始 query string，必须用 URI 原样传入，避免二次编码破坏签名。
                .uri(URI.create(url))
                .retrieve()
                .body(byte[].class);
        if (audio == null || audio.length == 0) {
            throw new IllegalStateException("DashScope Qwen-TTS 音频 URL 返回了空内容");
        }
        return audio;
    }

    /**
     * 校验 API Key，避免把无效请求打到远端后才暴露不清晰的鉴权错误。
     */
    private void requireApiKey() {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("DashScope API Key 未配置，请设置 DASHSCOPE_API_KEY 或 KONG_VOICE_AGENT_DASHSCOPE_API_KEY");
        }
    }

    /**
     * 创建带超时的 RestClient，避免外部服务异常时无限阻塞 TTS 流水线。
     */
    private RestClient restClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(properties.timeoutMs());
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
