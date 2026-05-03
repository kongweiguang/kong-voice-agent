package io.github.kongweiguang.voice.agent.extension.tts.qwen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;
import io.github.kongweiguang.voice.agent.tts.TtsOrchestrator;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 基于阿里云百炼 Qwen TTS Realtime 接口的 TTS 编排器。
 *
 * @author kongweiguang
 */
@RequiredArgsConstructor
public class QwenTtsOrchestrator implements TtsOrchestrator {
    /**
     * 等待实时会话结束的额外缓冲毫秒数，避免边界耗时误判。
     */
    private static final long REALTIME_TIMEOUT_BUFFER_MS = 1000L;

    /**
     * 每个 turn 独立累计待合成文本，保证异步 turnId 之间不会串音。
     */
    private final ConcurrentMap<String, String> pendingTextByTurn = new ConcurrentHashMap<>();

    /**
     * Qwen TTS 服务配置。
     */
    private final QwenTtsProperties properties;

    /**
     * Qwen TTS Realtime 会话工厂。
     */
    private final QwenTtsRealtimeSessionFactory sessionFactory;

    /**
     * 将文本片段按句子边界合成为一个或多个音频块。
     */
    @Override
    public List<TtsChunk> synthesize(String turnId, Integer startSeq, String text, Boolean lastTextChunk) {
        List<TtsChunk> chunks = new ArrayList<>();
        synthesizeStreaming(turnId, startSeq, text, lastTextChunk, chunks::add);
        return chunks;
    }

    /**
     * 将文本片段按句子边界提交给 Qwen TTS Realtime，并把音频增量立即回调给流水线。
     */
    @Override
    public void synthesizeStreaming(String turnId, Integer startSeq, String text, Boolean lastTextChunk,
                                    Consumer<TtsChunk> chunkConsumer) {
        String pendingText = takeReadyText(turnId, text, lastTextChunk);
        if (pendingText.isBlank()) {
            return;
        }
        synthesizeRealtime(turnId, startSeq, pendingText, lastTextChunk, chunkConsumer);
    }

    /**
     * 创建一次 Qwen TTS Realtime 会话并同步等待本次文本合成完成。
     */
    private void synthesizeRealtime(String turnId, Integer startSeq, String text, Boolean lastTextChunk,
                                    Consumer<TtsChunk> chunkConsumer) {
        requireApiKey();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<byte[]> pendingAudio = new AtomicReference<>();
        AtomicInteger nextSeq = new AtomicInteger(startSeq);
        AtomicInteger emittedAudioCount = new AtomicInteger();
        QwenTtsRealtimeSession session = sessionFactory.create(properties, message ->
                handleRealtimeEvent(message, turnId, text, lastTextChunk, chunkConsumer, pendingAudio,
                        nextSeq, emittedAudioCount, failure, done));
        try {
            session.connect();
            session.appendText(text);
            if (isCommitMode()) {
                session.commit();
            } else {
                session.finish();
            }
            boolean completed = done.await(properties.timeoutMs() + REALTIME_TIMEOUT_BUFFER_MS, TimeUnit.MILLISECONDS);
            if (!completed) {
                throw new IllegalStateException("Qwen TTS Realtime 读取超时");
            }
            if (failure.get() != null) {
                throw new IllegalStateException("Qwen TTS Realtime 读取失败", failure.get());
            }
            if (pendingAudio.get() == null && emittedAudioCount.get() == 0) {
                throw new IllegalStateException("Qwen TTS Realtime 返回了空音频");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qwen TTS Realtime 等待被中断", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Qwen TTS Realtime 调用失败，请检查 API Key、模型名称、音色名称和网络连通性", ex);
        } finally {
            session.close();
        }
    }

    /**
     * 处理 Qwen TTS Realtime 服务端事件。
     */
    private void handleRealtimeEvent(JsonObject message, String turnId, String text, Boolean lastTextChunk,
                                     Consumer<TtsChunk> chunkConsumer, AtomicReference<byte[]> pendingAudio,
                                     AtomicInteger nextSeq, AtomicInteger emittedAudioCount,
                                     AtomicReference<Throwable> failure, CountDownLatch done) {
        String type = textValue(message, "type");
        switch (type) {
            case "response.audio.delta" -> handleAudioDelta(message, turnId, text, chunkConsumer,
                    pendingAudio, nextSeq, emittedAudioCount);
            case "response.done", "session.finished" -> {
                emitTerminalAudio(turnId, text, lastTextChunk, chunkConsumer, pendingAudio, nextSeq, emittedAudioCount);
                done.countDown();
            }
            case "error", "connection.closed" -> {
                failure.set(new IllegalStateException(message.toString()));
                done.countDown();
            }
            default -> {
                // 其他 session.created、session.updated 等事件只用于观测，不影响音频输出。
            }
        }
    }

    /**
     * 处理一段 Base64 PCM 音频增量，并保留最后一段用于设置 last 标记。
     */
    private void handleAudioDelta(JsonObject message, String turnId, String text, Consumer<TtsChunk> chunkConsumer,
                                  AtomicReference<byte[]> pendingAudio, AtomicInteger nextSeq,
                                  AtomicInteger emittedAudioCount) {
        String audioBase64 = textValue(message, "delta");
        if (audioBase64.isBlank()) {
            return;
        }
        byte[] currentAudio = Base64.getDecoder().decode(audioBase64);
        byte[] previous = pendingAudio.getAndSet(currentAudio);
        if (previous != null && previous.length > 0) {
            chunkConsumer.accept(new TtsChunk(turnId, nextSeq.getAndIncrement(), false, previous, text));
            emittedAudioCount.incrementAndGet();
        }
    }

    /**
     * 输出最后一段音频，并把 last 标记映射到当前 LLM 文本片段的结束状态。
     */
    private void emitTerminalAudio(String turnId, String text, Boolean lastTextChunk, Consumer<TtsChunk> chunkConsumer,
                                   AtomicReference<byte[]> pendingAudio, AtomicInteger nextSeq,
                                   AtomicInteger emittedAudioCount) {
        byte[] lastAudio = pendingAudio.getAndSet(null);
        if (lastAudio != null && lastAudio.length > 0) {
            chunkConsumer.accept(new TtsChunk(turnId, nextSeq.get(), lastTextChunk, lastAudio, text));
            emittedAudioCount.incrementAndGet();
        }
    }

    /**
     * 从 JsonObject 中安全读取字符串字段。
     */
    private String textValue(JsonObject message, String fieldName) {
        if (message == null || !message.has(fieldName)) {
            return "";
        }
        JsonElement element = message.get(fieldName);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    /**
     * 判断当前配置是否为客户端主动提交模式。
     */
    private boolean isCommitMode() {
        return "commit".equalsIgnoreCase(properties.mode());
    }

    /**
     * 将当前文本追加到 turn 级缓冲区。
     */
    private String appendPendingText(String turnId, String text) {
        if (text.isBlank()) {
            return pendingTextByTurn.getOrDefault(turnId, "");
        }
        return pendingTextByTurn.merge(turnId, text, String::concat);
    }

    /**
     * 判断当前 turn 缓冲文本是否已经适合提交给 TTS。
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
            return "";
        }
        pendingTextByTurn.remove(turnId);
        return pendingText;
    }

    /**
     * 判断文本是否以可作为合成边界的句末标点结束。
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
     * 清理 LLM 输出中不适合朗读的控制字符和思考标签。
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
     * 判断文本中是否包含至少一个可朗读字符，避免纯标点触发外部 TTS 调用。
     */
    private boolean hasSpeakableCharacter(String text) {
        return text.codePoints().anyMatch(codePoint ->
                Character.isLetterOrDigit(codePoint)
                        || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    /**
     * 校验 Qwen TTS API Key 是否已配置。
     */
    private void requireApiKey() {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("Qwen TTS API Key 未配置，请设置 DASHSCOPE_API_KEY 或 KONG_VOICE_AGENT_QWEN_API_KEY");
        }
    }
}
