package io.github.kongweiguang.voice.agent.integration.qwen;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 使用阿里云官方 DashScope Java SDK 直接验证 Qwen-ASR-Realtime，隔离项目适配层影响。
 *
 * @author kongweiguang
 */
@Tag("audio")
@Tag("integration")
@Tag("protocol")
@DisplayName("Qwen 官方 Realtime ASR 烟雾测试")
class QwenOfficialRealtimeAsrSmokeTest {
    /**
     * 16kHz 单声道 PCM16 每 20ms 的字节数。
     */
    private static final int PCM_CHUNK_BYTES = 640;

    /**
     * 结束会话后等待服务端返回最终稿的超时时间。
     */
    private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 默认的 MP3 测试音频路径。
     */
    private static final Path DEFAULT_MP3 = Path.of(
            "C:\\dev\\java\\xm\\my\\kong-voice-agent\\kong-voice-agent-app\\src\\main\\resources\\bca.mp3");

    @Test
    @DisplayName("官方 SDK 手动提交模式可以识别 MP3 音频")
    void shouldTranscribeMp3ViaOfficialSdk() throws Exception {
        String apiKey = resolveApiKey();
        Path mp3Path = resolveAudioPath();
        assumeTrue(apiKey != null && !apiKey.isBlank(), "未提供 DashScope API Key，跳过官方联调测试");
        assumeTrue(Files.exists(mp3Path), "未找到联调音频文件，跳过官方联调测试");

        byte[] pcm = convertMp3ToPcm16(mp3Path);
        TestCallback callback = new TestCallback();
        OmniRealtimeConversation conversation = new OmniRealtimeConversation(param(apiKey), callback);
        try {
            conversation.connect();
            conversation.updateSession(config());
            appendAudioInChunks(conversation, pcm);
            conversation.commit();
            conversation.endSession();

            assertThat(callback.awaitFinished()).as(callback.eventSummary()).isTrue();
            assertThat(callback.finalTranscript()).as(callback.eventSummary()).isNotBlank();
            System.out.println("Qwen official ASR final transcript: " + callback.finalTranscript());
            System.out.println(callback.eventSummary());
        } finally {
            closeQuietly(conversation);
        }
    }

    /**
     * 解析联调时使用的 API Key。
     */
    private String resolveApiKey() {
        String[] candidates = {
                System.getProperty("dashscope.apiKey"),
                System.getenv("KONG_VOICE_AGENT_QWEN_API_KEY"),
                System.getenv("DASHSCOPE_API_KEY")
        };
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    /**
     * 解析联调音频路径，允许命令行覆盖默认资源文件。
     */
    private Path resolveAudioPath() {
        String override = System.getProperty("qwen.asr.testAudio");
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        return DEFAULT_MP3;
    }

    /**
     * 构造官方 SDK 连接参数。
     */
    private OmniRealtimeParam param(String apiKey) {
        return OmniRealtimeParam.builder()
                .model("qwen3-asr-flash-realtime")
                .url("wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
                .apikey(apiKey)
                .build();
    }

    /**
     * 构造官方文档推荐的 Manual 模式配置。
     */
    private OmniRealtimeConfig config() {
        OmniRealtimeTranscriptionParam transcriptionParam = new OmniRealtimeTranscriptionParam();
        transcriptionParam.setLanguage("zh");
        transcriptionParam.setInputSampleRate(16000);
        transcriptionParam.setInputAudioFormat("pcm");
        return OmniRealtimeConfig.builder()
                .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
                .enableTurnDetection(false)
                .transcriptionConfig(transcriptionParam)
                .build();
    }

    /**
     * 按 20ms 分片发送 PCM，模拟官方推荐的实时上传节奏。
     */
    private void appendAudioInChunks(OmniRealtimeConversation conversation, byte[] pcm) {
        for (int offset = 0; offset < pcm.length; offset += PCM_CHUNK_BYTES) {
            int end = Math.min(offset + PCM_CHUNK_BYTES, pcm.length);
            byte[] chunk = new byte[end - offset];
            System.arraycopy(pcm, offset, chunk, 0, chunk.length);
            conversation.appendAudio(Base64.getEncoder().encodeToString(chunk));
        }
    }

    /**
     * 使用 ffmpeg 将 MP3 转成 16kHz / mono / s16le 原始 PCM。
     */
    private byte[] convertMp3ToPcm16(Path mp3Path) throws IOException, InterruptedException {
        List<String> command = List.of(
                "ffmpeg",
                "-v", "error",
                "-i", mp3Path.toString(),
                "-f", "s16le",
                "-acodec", "pcm_s16le",
                "-ac", "1",
                "-ar", "16000",
                "-"
        );
        Process process = new ProcessBuilder(command).start();
        byte[] stdout;
        String stderr;
        try (InputStream inputStream = process.getInputStream();
             InputStream errorStream = process.getErrorStream()) {
            stdout = readAllBytes(inputStream);
            stderr = new String(readAllBytes(errorStream));
        }
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new IllegalStateException("ffmpeg 转换 MP3 超时");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("ffmpeg 转换 MP3 失败: " + stderr);
        }
        return stdout;
    }

    /**
     * 读取输入流中的所有字节。
     */
    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    /**
     * SDK 在 session.finished 后可能已经主动关闭连接，这里静默忽略重复关闭异常。
     */
    private void closeQuietly(OmniRealtimeConversation conversation) {
        try {
            conversation.close();
        } catch (RuntimeException ignored) {
            // 官方 SDK 已经进入 closed 状态时再次 close 会抛 conversation is already closed。
        }
    }

    /**
     * 收集官方 SDK 回调事件，用于断言最终稿和输出诊断日志。
     */
    private static final class TestCallback extends OmniRealtimeCallback {
        /**
         * 收集事件类型与核心字段，便于失败时快速定位。
         */
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());

        /**
         * 最终转写文本。
         */
        private final AtomicReference<String> finalTranscript = new AtomicReference<>("");

        /**
         * 连接关闭或会话结束信号。
         */
        private final CountDownLatch finished = new CountDownLatch(1);

        @Override
        public void onEvent(JsonObject message) {
            String type = textValue(message, "type");
            events.add(type + " => " + message);
            if (Objects.equals(type, "conversation.item.input_audio_transcription.completed")) {
                finalTranscript.set(extractTranscript(message));
            }
            if (Objects.equals(type, "session.finished")) {
                finished.countDown();
            }
        }

        @Override
        public void onClose(int code, String reason) {
            events.add("connection.closed => code=" + code + ", reason=" + reason);
            finished.countDown();
        }

        /**
         * 等待会话结束。
         */
        private boolean awaitFinished() throws InterruptedException {
            return finished.await(SESSION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        /**
         * 返回最终转写文本。
         */
        private String finalTranscript() {
            return finalTranscript.get();
        }

        /**
         * 输出精简事件日志。
         */
        private String eventSummary() {
            return String.join(System.lineSeparator(), events);
        }

        /**
         * 安全读取事件字符串字段。
         */
        private String textValue(JsonObject message, String fieldName) {
            if (message == null || !message.has(fieldName) || message.get(fieldName).isJsonNull()) {
                return "";
            }
            return message.get(fieldName).getAsString();
        }

        /**
         * 从官方 completed 事件中提取 transcript。
         */
        private String extractTranscript(JsonObject message) {
            if (message == null || !message.has("transcript") || message.get("transcript").isJsonNull()) {
                return "";
            }
            return message.get("transcript").getAsString().trim();
        }
    }
}
