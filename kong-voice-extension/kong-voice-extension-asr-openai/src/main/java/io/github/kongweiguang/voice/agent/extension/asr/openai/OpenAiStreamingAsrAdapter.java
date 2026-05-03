package io.github.kongweiguang.voice.agent.extension.asr.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.v1.http.client.Req;
import io.github.kongweiguang.v1.http.client.Res;
import io.github.kongweiguang.v1.http.client.entity.FilePart;
import io.github.kongweiguang.v1.http.client.spec.HttpReqSpec;
import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.v1.json.Json;
import lombok.RequiredArgsConstructor;

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
        try (Res response = requestSpec(pcm).ok()) {
            if (!response.isOk()) {
                throw new IllegalStateException("OpenAI ASR HTTP 状态码: " + response.code() + "，响应体: " + response.str());
            }
            return readTranscript(response.str());
        } catch (Exception ex) {
            throw new IllegalStateException("OpenAI ASR 调用失败，请检查 API Key、模型名称和网络连通性", ex);
        }
    }

    /**
     * 构造 OpenAI 转写请求体，使用 multipart/form-data 直接上传 WAV 文件。
     */
    private HttpReqSpec requestSpec(byte[] pcm) {
        HttpReqSpec spec = Req.multipart(properties.baseUrl() + properties.transcriptionsPath())
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .bearer(properties.apiKey());
        // kong-http 当前公开 form/file 方法会校验 contentType 分支；这里通过可读集合写入字段，保持请求仍由 kong-http 编码发送。
        spec.form().put("model", properties.model());
        spec.form().put("response_format", "json");
        if (properties.language() != null && !properties.language().isBlank()) {
            spec.form().put("language", properties.language());
        }
        spec.files().add(new FilePart("file", "audio.wav", PcmWaveEncoder.encode(pcm, format)));
        return spec;
    }

    /**
     * 从 OpenAI 转写响应中读取识别文本。
     */
    private String readTranscript(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("OpenAI ASR 返回了空转写结果");
        }
        try {
            JsonNode root = Json.node(response);
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
     * 追加同一 turn 的 PCM 字节，保持进入 ASR 时的原始顺序。
     */
    private byte[] concat(byte[] left, byte[] right) {
        byte[] out = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }
}
