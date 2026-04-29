package io.github.kongweiguang.voice.agent.extension.asr.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于 OpenAI Audio Transcriptions 接口的会话级 ASR 适配器。
 *
 * @author kongweiguang
 */
@RequiredArgsConstructor
public class OpenAiStreamingAsrAdapter implements StreamingAsrAdapter {
    /**
     * 当前会话使用的音频格式，用于把 PCM 包装成 WAV 后提交给 OpenAI 转写接口。
     */
    private final AudioFormatSpec format;

    /**
     * OpenAI ASR 服务配置。
     */
    private final OpenAiAsrProperties properties;

    /**
     * JSON 解析器，用于读取 OpenAI 转写响应。
     */
    private final ObjectMapper objectMapper;

    /**
     * 每个 turn 已收到的 PCM 原始字节，commit 时整体转成 WAV 文件上传。
     */
    private final ConcurrentMap<String, byte[]> pcmByTurn = new ConcurrentHashMap<>();

    /**
     * 接收音频并缓存到当前 turn。当前同步 OpenAI 转写接口不会生成假的 partial。
     */
    @Override
    public Optional<AsrUpdate> acceptAudio(String turnId, byte[] pcm) {
        pcmByTurn.merge(turnId, Arrays.copyOf(pcm, pcm.length), this::concat);
        return Optional.empty();
    }

    /**
     * 将当前 turn 累计音频提交到 OpenAI ASR 接口，并返回最终转写。
     */
    @Override
    public AsrUpdate commitTurn(String turnId) {
        byte[] pcm = pcmByTurn.remove(turnId);
        if (pcm == null || pcm.length == 0) {
            throw new IllegalStateException("当前 turn 没有可提交给 OpenAI ASR 的 PCM 音频");
        }
        return AsrUpdate.finalUpdate(turnId, transcript(pcm));
    }

    /**
     * 关闭 ASR 时释放累计音频状态。
     */
    @Override
    public void close() {
        pcmByTurn.clear();
    }

    /**
     * 调用 OpenAI Audio Transcriptions 接口，失败时直接抛出异常。
     */
    private String transcript(byte[] pcm) {
        requireApiKey();
        try {
            String response = restClient().post()
                    .uri(properties.transcriptionsPath())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .body(requestBody(pcm))
                    .retrieve()
                    .body(String.class);
            return readTranscript(response);
        } catch (Exception ex) {
            throw new IllegalStateException("OpenAI ASR 调用失败，请检查 API Key、模型名称和网络连通性", ex);
        }
    }

    /**
     * 构造 OpenAI 转写请求体，使用 multipart/form-data 直接上传 WAV 文件。
     */
    private MultiValueMap<String, Object> requestBody(byte[] pcm) {
        byte[] wav = PcmWaveEncoder.encode(pcm, format);
        MultiValueMap<String, Object> request = new LinkedMultiValueMap<>();
        request.add("model", properties.model());
        request.add("response_format", "json");
        if (properties.language() != null && !properties.language().isBlank()) {
            request.add("language", properties.language());
        }
        request.add("file", new ByteArrayResource(wav) {
            @Override
            public String getFilename() {
                return "audio.wav";
            }
        });
        return request;
    }

    /**
     * 从 OpenAI 转写响应中读取识别文本。
     */
    private String readTranscript(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("OpenAI ASR 返回了空转写结果");
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode text = root.path("text");
            if (text.isTextual() && !text.asText().isBlank()) {
                return text.asText().trim();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("OpenAI ASR 响应格式不符合预期", ex);
        }
        throw new IllegalStateException("OpenAI ASR 响应中缺少 text 字段");
    }

    /**
     * 校验 API Key，避免发出不可鉴权请求。
     */
    private void requireApiKey() {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("OpenAI API Key 未配置，请设置 OPENAI_API_KEY 或 KONG_VOICE_AGENT_OPENAI_API_KEY");
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
