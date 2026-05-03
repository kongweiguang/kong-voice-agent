package io.github.kongweiguang.voice.agent.session;

import io.github.kongweiguang.voice.agent.metrics.TurnMetricsSnapshot;

import java.time.Duration;
import java.time.Instant;

/**
 * 当前活跃 turn 的阶段时间采集器。
 *
 * @author kongweiguang
 */
public class TurnMetrics {
    /**
     * 当前正在采集指标的 turnId。
     */
    private String turnId;

    /**
     * 当前 turn 来源，默认按音频处理。
     */
    private String source = "audio";

    /**
     * 首个音频块进入流水线的时间。
     */
    private Instant firstAudioAt;

    /**
     * ASR 首次返回 partial 或 final 的时间。
     */
    private Instant asrFirstResponseAt;

    /**
     * ASR 最终稿输出时间。
     */
    private Instant asrFinalAt;

    /**
     * 用户 turn committed 时间，也作为“用户说完话”的统计起点。
     */
    private Instant turnCommittedAt;

    /**
     * LLM 启动时间。
     */
    private Instant llmStartedAt;

    /**
     * LLM 首个非空文本片段时间。
     */
    private Instant llmFirstChunkAt;

    /**
     * LLM 完成时间。
     */
    private Instant llmCompletedAt;

    /**
     * 首次向 TTS 提交可合成文本的时间。
     */
    private Instant ttsStartedAt;

    /**
     * TTS 首个音频块输出时间。
     */
    private Instant ttsFirstChunkAt;

    /**
     * TTS 最后一个音频块输出时间。
     */
    private Instant ttsCompletedAt;

    /**
     * 为新的 turn 重置采集器。
     */
    public synchronized void reset(String turnId) {
        this.turnId = turnId;
        this.source = "audio";
        this.firstAudioAt = null;
        this.asrFirstResponseAt = null;
        this.asrFinalAt = null;
        this.turnCommittedAt = null;
        this.llmStartedAt = null;
        this.llmFirstChunkAt = null;
        this.llmCompletedAt = null;
        this.ttsStartedAt = null;
        this.ttsFirstChunkAt = null;
        this.ttsCompletedAt = null;
    }

    /**
     * 清空当前采集状态。
     */
    public synchronized void clear() {
        reset(null);
    }

    /**
     * 设置 turn 来源。
     */
    public synchronized void markSource(String source) {
        if (source != null && !source.isBlank()) {
            this.source = source;
        }
    }

    /**
     * 记录首个音频块进入时间。
     *
     * @return 当前调用是否首次设置成功
     */
    public synchronized boolean markAudioReceived(Instant at) {
        if (firstAudioAt != null || turnId == null) {
            return false;
        }
        firstAudioAt = at;
        return true;
    }

    /**
     * 记录 ASR 首次响应时间。
     *
     * @return 当前调用是否首次设置成功
     */
    public synchronized boolean markAsrFirstResponse(Instant at) {
        if (asrFirstResponseAt != null || turnId == null) {
            return false;
        }
        asrFirstResponseAt = at;
        return true;
    }

    /**
     * 记录 ASR 最终稿输出时间。
     *
     * @return 当前调用是否首次设置成功
     */
    public synchronized boolean markAsrFinal(Instant at) {
        if (asrFinalAt != null || turnId == null) {
            return false;
        }
        asrFinalAt = at;
        return true;
    }

    /**
     * 记录用户 turn committed 时间。
     */
    public synchronized void markTurnCommitted(Instant at) {
        if (turnCommittedAt == null && turnId != null) {
            turnCommittedAt = at;
        }
    }

    /**
     * 记录 LLM 启动时间。
     */
    public synchronized void markLlmStarted(Instant at) {
        if (llmStartedAt == null && turnId != null) {
            llmStartedAt = at;
        }
    }

    /**
     * 记录 LLM 首个非空文本片段时间。
     *
     * @return 当前调用是否首次设置成功
     */
    public synchronized boolean markLlmFirstChunk(Instant at) {
        if (llmFirstChunkAt != null || turnId == null) {
            return false;
        }
        llmFirstChunkAt = at;
        return true;
    }

    /**
     * 记录 LLM 完成时间。
     *
     * @return 当前调用是否首次设置成功
     */
    public synchronized boolean markLlmCompleted(Instant at) {
        if (llmCompletedAt != null || turnId == null) {
            return false;
        }
        llmCompletedAt = at;
        return true;
    }

    /**
     * 记录首次向 TTS 提交文本的时间。
     *
     * @return 当前调用是否首次设置成功
     */
    public synchronized boolean markTtsStarted(Instant at) {
        if (ttsStartedAt != null || turnId == null) {
            return false;
        }
        ttsStartedAt = at;
        return true;
    }

    /**
     * 记录 TTS 首包时间。
     *
     * @return 当前调用是否首次设置成功
     */
    public synchronized boolean markTtsFirstChunk(Instant at) {
        if (ttsFirstChunkAt != null || turnId == null) {
            return false;
        }
        ttsFirstChunkAt = at;
        return true;
    }

    /**
     * 记录 TTS 最后一包时间。
     *
     * @return 当前调用是否首次设置成功
     */
    public synchronized boolean markTtsCompleted(Instant at) {
        if (ttsCompletedAt != null || turnId == null) {
            return false;
        }
        ttsCompletedAt = at;
        return true;
    }

    /**
     * 生成当前 turn 的耗时快照。
     */
    public synchronized TurnMetricsSnapshot snapshot(String stage) {
        return new TurnMetricsSnapshot(
                stage,
                source,
                between(firstAudioAt, asrFirstResponseAt),
                between(firstAudioAt, asrFinalAt),
                between(llmStartedAt, llmFirstChunkAt),
                between(llmStartedAt, llmCompletedAt),
                between(ttsStartedAt, ttsFirstChunkAt),
                between(ttsStartedAt, ttsCompletedAt),
                between(turnCommittedAt, llmFirstChunkAt),
                between(turnCommittedAt, ttsFirstChunkAt)
        );
    }

    /**
     * 判断当前采集器是否归属于指定 turn。
     */
    public synchronized boolean matches(String turnId) {
        return this.turnId != null && this.turnId.equals(turnId);
    }

    /**
     * 安全计算两个时间点之间的毫秒差。
     */
    private Long between(Instant start, Instant end) {
        if (start == null || end == null) {
            return null;
        }
        return Math.max(Duration.between(start, end).toMillis(), 0L);
    }
}
