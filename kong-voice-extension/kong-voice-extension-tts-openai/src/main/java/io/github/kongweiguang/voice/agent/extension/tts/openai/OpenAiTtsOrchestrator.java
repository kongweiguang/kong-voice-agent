package io.github.kongweiguang.voice.agent.extension.tts.openai;

import io.github.kongweiguang.voice.agent.tts.TtsChunk;
import io.github.kongweiguang.voice.agent.tts.TtsOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 基于 OpenAI Audio Speech 接口的 TTS 编排器。
 *
 * @author kongweiguang
 */
@RequiredArgsConstructor
public class OpenAiTtsOrchestrator implements TtsOrchestrator {
    /**
     * 每个 turn 独立累计待合成文本，保证异步 turnId 之间不会串音。
     */
    private final ConcurrentMap<String, String> pendingTextByTurn = new ConcurrentHashMap<>();

    /**
     * OpenAI TTS 服务配置。
     */
    private final OpenAiTtsProperties properties;

    @Override
    public List<TtsChunk> synthesize(String turnId, Integer startSeq, String text, Boolean lastTextChunk) {
        String pendingText = takeReadyText(turnId, text, lastTextChunk);
        if (pendingText.isBlank()) {
            return List.of();
        }
        return List.of(new TtsChunk(turnId, startSeq, lastTextChunk, speechAudio(pendingText), pendingText));
    }

    @Override
    public void synthesizeStreaming(String turnId, Integer startSeq, String text, Boolean lastTextChunk,
                                    Consumer<TtsChunk> chunkConsumer) {
        synthesize(turnId, startSeq, text, lastTextChunk).forEach(chunkConsumer);
    }

    /**
     * 调用 OpenAI TTS 接口，失败时直接抛出异常，交由流水线转换为 error 事件。
     */
    private byte[] speechAudio(String text) {
        requireApiKey();
        try {
            byte[] audio = restClient().post()
                    .uri(properties.speechPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .body(requestBody(text))
                    .retrieve()
                    .body(byte[].class);
            if (audio == null || audio.length == 0) {
                throw new IllegalStateException("OpenAI TTS 返回了空音频");
            }
            return audio;
        } catch (Exception ex) {
            throw new IllegalStateException("OpenAI TTS 调用失败，请检查 API Key、模型名称和网络连通性", ex);
        }
    }

    /**
     * 构造 OpenAI TTS 请求体。
     */
    private Map<String, Object> requestBody(String text) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.model());
        request.put("input", text);
        request.put("voice", properties.voice());
        request.put("response_format", properties.responseFormat());
        if (properties.instructions() != null && !properties.instructions().isBlank()) {
            request.put("instructions", properties.instructions());
        }
        return request;
    }

    private String appendPendingText(String turnId, String text) {
        if (text.isBlank()) {
            return pendingTextByTurn.getOrDefault(turnId, "");
        }
        return pendingTextByTurn.merge(turnId, text, String::concat);
    }

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
            return "";
        }
        pendingTextByTurn.remove(turnId);
        return pendingText;
    }

    private boolean endsWithSentencePunctuation(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char last = text.charAt(text.length() - 1);
        return last == '。' || last == '！' || last == '？' || last == '；'
                || last == '.' || last == '!' || last == '?' || last == ';';
    }

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

    private boolean hasSpeakableCharacter(String text) {
        return text.codePoints().anyMatch(codePoint ->
                Character.isLetterOrDigit(codePoint)
                        || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private void requireApiKey() {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("OpenAI API Key 未配置，请设置 OPENAI_API_KEY 或 KONG_VOICE_AGENT_OPENAI_API_KEY");
        }
    }

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
