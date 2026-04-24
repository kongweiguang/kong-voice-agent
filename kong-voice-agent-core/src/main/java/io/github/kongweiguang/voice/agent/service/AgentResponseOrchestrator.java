package io.github.kongweiguang.voice.agent.service;

import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.hook.VoicePipelineHook;
import io.github.kongweiguang.voice.agent.llm.LlmChunk;
import io.github.kongweiguang.voice.agent.llm.LlmOrchestrator;
import io.github.kongweiguang.voice.agent.llm.LlmRequest;
import io.github.kongweiguang.voice.agent.model.AgentEvent;
import io.github.kongweiguang.voice.agent.model.EventType;
import io.github.kongweiguang.voice.agent.model.payload.AgentTextChunkPayload;
import io.github.kongweiguang.voice.agent.model.payload.AgentThinkingPayload;
import io.github.kongweiguang.voice.agent.model.payload.ErrorPayload;
import io.github.kongweiguang.voice.agent.model.payload.TtsAudioChunkPayload;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.session.TurnLifecycleState;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;
import io.github.kongweiguang.voice.agent.tts.TtsOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.socket.WebSocketSession;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 编排 turn 提交后的 Agent 响应阶段，隔离 LLM、TTS、错误收口和播报状态维护。
 *
 * @author kongweiguang
 */
@RequiredArgsConstructor
public class AgentResponseOrchestrator {
    /**
     * LLM 编排边界，真实业务可通过 Bean 覆盖。
     */
    private final LlmOrchestrator llmOrchestrator;

    /**
     * TTS 编排边界，真实业务可通过 Bean 覆盖。
     */
    private final TtsOrchestrator ttsOrchestrator;

    /**
     * WebSocket 下行事件发送器。
     */
    private final PlaybackDispatcher dispatcher;

    /**
     * Agent 响应阶段虚拟线程执行器。
     */
    private final Executor agentTaskExecutor;

    /**
     * 业务扩展 hook，按注册顺序观察响应阶段节点。
     */
    private final List<VoicePipelineHook> hooks;


    /**
     * 仅在当前已提交 turn 的 ASR 最终结果存在后启动 LLM。
     */
    public void startAfterCommit(SessionState session, WebSocketSession ws, AsrUpdate fin) {
        if (!Boolean.TRUE.equals(fin.fin()) || !session.isCurrentTurn(fin.turnId())) {
            return;
        }
        // final transcript 是 LLM 请求的唯一用户输入来源，partial 不能越过该边界。
        session.finalTranscript(fin.transcript());
        session.lifecycleState(TurnLifecycleState.AGENT_THINKING);
        session.activeLlmTurnId(fin.turnId());
        LlmRequest request = new LlmRequest(session.sessionId(), fin.turnId(), fin.transcript());
        hooks.forEach(hook -> hook.beforeLlm(session, ws, request));
        dispatcher.send(ws, AgentEvent.of(EventType.agent_thinking, session.sessionId(), fin.turnId(), new AgentThinkingPayload(fin.transcript())));
        CompletableFuture.runAsync(() -> streamLlm(session, ws, request), agentTaskExecutor);
    }

    /**
     * 执行 LLM 流式响应并把每个文本片段继续交给 TTS。
     */
    private void streamLlm(SessionState session, WebSocketSession ws, LlmRequest request) {
        AtomicInteger ttsSeq = new AtomicInteger();
        AtomicBoolean failed = new AtomicBoolean(false);
        try {
            llmOrchestrator.stream(request, chunk -> {
                // LLM 回调内捕获 TTS 失败，避免异常逃逸到流式模型或 Reactor 回调线程。
                if (failed.get()) {
                    return;
                }
                try {
                    handleLlmChunk(session, ws, chunk, ttsSeq);
                } catch (Exception ex) {
                    failed.set(true);
                    publishFailure(session, ws, request.turnId(), "tts_failed", ex);
                }
            });
        } catch (Exception ex) {
            failed.set(true);
            publishFailure(session, ws, request.turnId(), "llm_failed", ex);
        }
    }

    /**
     * 发布 LLM 文本，并将当前片段直接提交给 TTS。
     */
    private void handleLlmChunk(SessionState session, WebSocketSession ws, LlmChunk chunk, AtomicInteger ttsSeq) {
        if (!session.isCurrentTurn(chunk.turnId())) {
            return;
        }
        boolean hasText = chunk.text() != null && !chunk.text().isEmpty();
        if (!hasText) {
            if (Boolean.TRUE.equals(chunk.last())) {
                // 末尾空 chunk 只用于关闭本轮 Agent 状态，不触发 TTS。
                completeAgentTurn(session, chunk.turnId());
            }
            return;
        }
        hooks.forEach(hook -> hook.onLlmChunk(session, ws, chunk));
        dispatcher.send(ws,
                AgentEvent.of(EventType.agent_text_chunk, session.sessionId(), chunk.turnId(),
                        new AgentTextChunkPayload(chunk.seq(), chunk.text(), chunk.last(), chunk.rawResponse())
                ));
        session.lifecycleState(TurnLifecycleState.AGENT_SPEAKING);
        session.agentSpeaking(true);
        session.activeTtsTurnId(chunk.turnId());
        synthesizeTts(session, ws, chunk, ttsSeq);
        if (Boolean.TRUE.equals(chunk.last()) && session.isCurrentTurn(chunk.turnId())) {
            completeAgentTurn(session, chunk.turnId());
        }
    }

    /**
     * 将当前 LLM 文本片段提交给 TTS，并维护本轮 TTS 音频序号。
     */
    private void synthesizeTts(SessionState session, WebSocketSession ws, LlmChunk chunk, AtomicInteger ttsSeq) {
        ttsOrchestrator.synthesizeStreaming(chunk.turnId(), ttsSeq.get(), chunk.text(), chunk.last(), ttsChunk -> {
            // TTS 可一次回调多个音频块，seq 在流水线边界统一递增，便于前端排队播放。
            publishTts(session, ws, ttsChunk);
            ttsSeq.incrementAndGet();
        });
    }

    /**
     * LLM/TTS 属于异步下游，失败时要转成协议 error，避免异常泄漏到 Reactor 订阅线程。
     */
    public void publishFailure(SessionState session, WebSocketSession ws, String turnId, String code, Exception ex) {
        if (!session.isCurrentTurn(turnId)) {
            return;
        }
        completeAgentTurn(session, turnId);
        dispatcher.send(ws, AgentEvent.of(EventType.error, session.sessionId(), turnId,
                new ErrorPayload(code, rootMessage(ex))));
    }

    /**
     * 当前 agent turn 正常结束或异常收口时统一清理会话播报标记。
     */
    private void completeAgentTurn(SessionState session, String turnId) {
        if (!session.isCurrentTurn(turnId)) {
            return;
        }
        session.agentSpeaking(false);
        session.activeLlmTurnId(null);
        session.activeTtsTurnId(null);
        session.lifecycleState(TurnLifecycleState.IDLE);
        ttsOrchestrator.cancelTurn(turnId);
    }

    /**
     * 发布前立即校验 turnId，丢弃过期 TTS 输出。
     */
    private void publishTts(SessionState session, WebSocketSession ws, TtsChunk chunk) {
        if (!session.isCurrentTurn(chunk.turnId())) {
            return;
        }
        hooks.forEach(hook -> hook.onTtsChunk(session, ws, chunk));
        dispatcher.send(ws, AgentEvent.of(EventType.tts_audio_chunk, session.sessionId(), chunk.turnId(),
                new TtsAudioChunkPayload(chunk.seq(), chunk.last(), chunk.text(), Base64.getEncoder().encodeToString(chunk.audio()))));
    }

    /**
     * 提取最内层异常说明，优先把底层服务的真实失败原因暴露给联调端。
     */
    private String rootMessage(Exception ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? ex.getMessage() : message;
    }
}
