package io.github.kongweiguang.voice.agent.integration.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于 DashScope Qwen-ASR 的会话级 ASR 适配器。
 *
 * @author kongweiguang
 */
@RequiredArgsConstructor
public class DashScopeStreamingAsrAdapter implements StreamingAsrAdapter {
    /**
     * 当前会话使用的音频格式，用于把 PCM 包装成 WAV 后提交给 Qwen-ASR。
     */
    private final AudioFormatSpec format;

    /**
     * DashScope Qwen-ASR 服务配置。
     */
    private final DashScopeAsrProperties properties;

    /**
     * JSON 解析器，用于读取 Qwen-ASR 的兼容模式响应。
     */
    private final ObjectMapper objectMapper;

    /**
     * 每个 turn 已收到的 PCM 原始字节，commit 时整体转成 WAV 的 data URL。
     */
    private final ConcurrentMap<String, byte[]> pcmByTurn = new ConcurrentHashMap<>();

    /**
     * 接收音频并缓存到当前 turn。同步 Qwen-ASR 接口不会生成假的 partial。
     */
    @Override
    public Optional<AsrUpdate> acceptAudio(String turnId, byte[] pcm) {
        // DashScope 当前接入的是单次 HTTP ASR，这里只按 turn 累计 PCM，不伪造 asr_partial。
        pcmByTurn.merge(turnId, Arrays.copyOf(pcm, pcm.length), this::concat);
        return Optional.empty();
    }

    /**
     * 将当前 turn 累计音频提交到 Qwen-ASR，并返回最终转写。
     */
    @Override
    public AsrUpdate commitTurn(String turnId) {
        // commit 是本轮音频的最终提交点，取出后删除缓存，避免同一 turn 被重复识别。
        byte[] pcm = pcmByTurn.remove(turnId);
        if (pcm == null || pcm.length == 0) {
            throw new IllegalStateException("当前 turn 没有可提交给 DashScope Qwen-ASR 的 PCM 音频");
        }
        return AsrUpdate.finalUpdate(turnId, dashScopeTranscript(pcm));
    }

    /**
     * 释放被打断或被新 turn 取代的 PCM 缓存，避免长连接多轮对话持续占用内存。
     */
    @Override
    public void cancelTurn(String turnId) {
        if (turnId != null) {
            pcmByTurn.remove(turnId);
        }
    }

    /**
     * 关闭 ASR 时释放累计音频状态。
     */
    @Override
    public void close() {
        pcmByTurn.clear();
    }

    /**
     * 调用 DashScope OpenAI 兼容 Chat Completions 接口，失败时直接抛出异常。
     */
    private String dashScopeTranscript(byte[] pcm) {
        requireApiKey();
        try {
            // 音频在请求体里以内联 WAV data URL 传递，不依赖临时文件或公网可访问 URL。
            String response = restClient().post()
                    .uri(properties.chatCompletionsPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .body(requestBody(pcm))
                    .retrieve()
                    .body(String.class);
            return readTranscript(response);
        } catch (Exception ex) {
            throw new IllegalStateException("DashScope Qwen-ASR 调用失败，请检查 API Key、模型名称和网络连通性", ex);
        }
    }

    /**
     * 构造 Qwen-ASR 兼容模式请求体，音频使用 WAV data URL，避免额外依赖公网文件 URL。
     */
    private Map<String, Object> requestBody(byte[] pcm) {
        byte[] wav = PcmWaveEncoder.encode(pcm, format);
        String audioDataUrl = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav);
        Map<String, Object> inputAudio = new LinkedHashMap<>();
        inputAudio.put("data", audioDataUrl);

        // DashScope OpenAI 兼容 ASR 使用 input_audio content item 承载音频。
        Map<String, Object> contentItem = new LinkedHashMap<>();
        contentItem.put("type", "input_audio");
        contentItem.put("input_audio", inputAudio);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(contentItem));

        Map<String, Object> asrOptions = new LinkedHashMap<>();
        asrOptions.put("enable_itn", properties.enableItn());
        if (properties.language() != null && !properties.language().isBlank()) {
            asrOptions.put("language", properties.language());
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.model());
        request.put("messages", List.of(message));
        request.put("stream", false);
        request.put("asr_options", asrOptions);
        return request;
    }

    /**
     * 从 OpenAI 兼容响应中读取识别文本。
     */
    private String readTranscript(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("DashScope Qwen-ASR 返回了空转写结果");
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isTextual() && !content.asText().isBlank()) {
                // 这里返回的文本会成为 asr_final，并作为后续 LLM 的唯一用户输入。
                return content.asText().trim();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("DashScope Qwen-ASR 响应格式不符合预期", ex);
        }
        throw new IllegalStateException("DashScope Qwen-ASR 响应中缺少 choices[0].message.content");
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
     * 创建带超时的 RestClient，避免外部服务异常时无限阻塞音频流水线。
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

    /**
     * 追加同一 turn 的 PCM 字节，保持进入 ASR 时的原始顺序。
     */
    private byte[] concat(byte[] left, byte[] right) {
        byte[] out = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }
}
