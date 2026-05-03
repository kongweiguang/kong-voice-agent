package io.github.kongweiguang.voice.agent.model.payload;

import io.github.kongweiguang.voice.agent.metrics.TurnMetricsSnapshot;

/**
 * 单个 turn 的阶段耗时指标。
 *
 * @param stage 当前指标快照对应阶段，例如 asr_final、llm_completed 或 tts_completed
 * @param source 当前 turn 来源，通常为 audio 或 text
 * @param asrResponseLatencyMs ASR 首次响应耗时，音频 turn 从首个音频块进入流水线开始计算
 * @param asrDurationMs ASR 总耗时，音频 turn 从首个音频块进入流水线到最终稿输出结束
 * @param llmResponseLatencyMs LLM 首字耗时，从启动 LLM 到收到首个非空文本片段
 * @param llmDurationMs LLM 总耗时，从启动 LLM 到收到最后一个完成片段
 * @param ttsResponseLatencyMs TTS 首包耗时，从第一次提交 TTS 合成到收到首个音频块
 * @param ttsDurationMs TTS 总耗时，从第一次提交 TTS 合成到收到最后一个音频块
 * @param speechEndToLlmFirstTokenMs 用户说完话到 LLM 开始输出首字耗时，以 turn committed 为起点
 * @param speechEndToTtsFirstChunkMs 用户说完话到 TTS 开始输出首包音频耗时，以 turn committed 为起点
 * @author kongweiguang
 */
public record TurnMetricsPayload(String stage,
                                 String source,
                                 Long asrResponseLatencyMs,
                                 Long asrDurationMs,
                                 Long llmResponseLatencyMs,
                                 Long llmDurationMs,
                                 Long ttsResponseLatencyMs,
                                 Long ttsDurationMs,
                                 Long speechEndToLlmFirstTokenMs,
                                 Long speechEndToTtsFirstChunkMs) implements AgentEventPayload {
    /**
     * 将内部快照转换为协议 payload。
     */
    public static TurnMetricsPayload from(TurnMetricsSnapshot snapshot) {
        return new TurnMetricsPayload(
                snapshot.stage(),
                snapshot.source(),
                snapshot.asrResponseLatencyMs(),
                snapshot.asrDurationMs(),
                snapshot.llmResponseLatencyMs(),
                snapshot.llmDurationMs(),
                snapshot.ttsResponseLatencyMs(),
                snapshot.ttsDurationMs(),
                snapshot.speechEndToLlmFirstTokenMs(),
                snapshot.speechEndToTtsFirstChunkMs()
        );
    }
}
