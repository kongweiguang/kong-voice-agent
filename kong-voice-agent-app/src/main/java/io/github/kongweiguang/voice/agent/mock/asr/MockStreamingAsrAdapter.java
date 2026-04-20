package io.github.kongweiguang.voice.agent.mock.asr;

import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * app 模块内的确定性 ASR 模拟实现，将累计音频时长转换为文本。
 *
 * @author kongweiguang
 */
@RequiredArgsConstructor
public class MockStreamingAsrAdapter implements StreamingAsrAdapter {
    /**
     * 当前会话使用的音频格式，用于把字节数换算成模拟时长。
     */
    private final AudioFormatSpec format;

    /**
     * 每个 turn 已收到的累计音频字节数。
     */
    private final ConcurrentMap<Long, AtomicInteger> bytesByTurn = new ConcurrentHashMap<>();

    /**
     * 接收音频并按累计时长生成可预测的 partial 文本。
     */
    @Override
    public Optional<AsrUpdate> acceptAudio(long turnId, byte[] pcm) {
        int bytes = bytesByTurn.computeIfAbsent(turnId, ignored -> new AtomicInteger()).addAndGet(pcm.length);
        int ms = bytes / Math.max(1, format.bytesPerMillisecond());
        if (ms < format.uploadChunkMs()) {
            return Optional.empty();
        }
        return Optional.of(AsrUpdate.partial(turnId, "mock partial " + ms + "ms"));
    }

    /**
     * 产出最终转写，并清理当前 turn 的模拟计数。
     */
    @Override
    public AsrUpdate commitTurn(long turnId) {
        int bytes = bytesByTurn.getOrDefault(turnId, new AtomicInteger()).get();
        int ms = bytes / Math.max(1, format.bytesPerMillisecond());
        String text = ms > 0 ? "mock final transcript for turn " + turnId + " (" + ms + "ms)" : "mock final transcript for turn " + turnId;
        bytesByTurn.remove(turnId);
        return AsrUpdate.finalUpdate(turnId, text);
    }

    /**
     * 关闭 mock ASR 时释放累计状态。
     */
    @Override
    public void close() {
        bytesByTurn.clear();
    }
}
