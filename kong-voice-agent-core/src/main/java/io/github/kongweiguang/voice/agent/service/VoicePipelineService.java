package io.github.kongweiguang.voice.agent.service;

import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.hook.VoicePipelineHook;
import io.github.kongweiguang.voice.agent.llm.LlmChunk;
import io.github.kongweiguang.voice.agent.llm.LlmOrchestrator;
import io.github.kongweiguang.voice.agent.llm.LlmRequest;
import io.github.kongweiguang.voice.agent.model.*;
import io.github.kongweiguang.voice.agent.model.payload.*;
import io.github.kongweiguang.voice.agent.playback.InterruptionManager;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.session.TurnLifecycleState;
import io.github.kongweiguang.voice.agent.tts.TtsChunk;
import io.github.kongweiguang.voice.agent.tts.TtsOrchestrator;
import io.github.kongweiguang.voice.agent.turn.TurnEvent;
import io.github.kongweiguang.voice.agent.vad.VadDecision;
import io.github.kongweiguang.voice.agent.vad.VadEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 编排音频接入、ASR、turn 提交、LLM、TTS 和打断流程，作为 Facade
 * 隐藏多个策略、工厂、hook 和状态机协作细节。
 *
 * @author kongweiguang
 */
@Service
@RequiredArgsConstructor
public class VoicePipelineService {
    /**
     * 语音活动检测器，负责把 PCM 转为说话概率。
     */
    private final VadEngine vadEngine;

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
     * 统一处理主动打断和插话打断的管理器。
     */
    private final InterruptionManager interruptionManager;

    /**
     * 音频处理虚拟线程执行器。
     */
    @Qualifier("audioTaskExecutor")
    private final Executor audioTaskExecutor;

    /**
     * LLM/TTS 下游任务虚拟线程执行器。
     */
    @Qualifier("agentTaskExecutor")
    private final Executor agentTaskExecutor;

    /**
     * 业务扩展 hook，按注册顺序观察关键流水线节点。
     */
    private final List<VoicePipelineHook> hooks;

    /**
     * 接收 WebSocket 线程送来的 PCM，并将耗时工作调度到 IO 线程之外。
     */
    public void acceptAudio(SessionState session, WebSocketSession ws, byte[] pcm) {
        session.lastAudioAt(Instant.now());
        session.pcmRingBuffer().write(pcm);
        session.preRollBuffer().write(pcm);
        hooks.forEach(hook -> hook.onAudioReceived(session, ws, pcm));
        CompletableFuture.runAsync(() -> processAudio(session, ws, pcm), audioTaskExecutor);
    }

    /**
     * 客户端强制结束音频：提交 ASR 最终结果后再启动 LLM。
     */
    public void commitAudioEnd(SessionState session, WebSocketSession ws) {
        long turnId = session.currentTurnId();
        if (turnId <= 0 || !session.isCurrentTurn(turnId)) {
            return;
        }
        AsrUpdate fin = session.asrAdapter().commitTurn(turnId);
        publishAsrFinal(session, ws, fin);
        startLlmAfterCommit(session, ws, fin);
    }

    /**
     * 接收客户端直接提交的文本。文本本身已经是完整用户输入，因此跳过 VAD/ASR，
     * 直接建立新的 committed turn，再沿用 LLM/TTS 下游流水线。
     */
    public void acceptText(SessionState session, WebSocketSession ws, String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("payload.text must not be blank");
        }
        hooks.forEach(hook -> hook.onTextReceived(session, ws, normalized));
        long turnId = session.agentSpeaking()
                ? interruptWithHooks(session, ws, "client_text")
                : session.nextTurnId();
        session.lastSpeechAt(Instant.now());
        session.lifecycleState(TurnLifecycleState.USER_TURN_COMMITTED);
        publishState(session, ws, TurnLifecycleState.USER_TURN_COMMITTED, "text");
        AsrUpdate fin = AsrUpdate.finalUpdate(turnId, normalized);
        publishAsrFinal(session, ws, fin, "text");
        startLlmAfterCommit(session, ws, fin);
    }

    /**
     * 客户端主动打断与插话打断复用同一条失效路径。
     */
    public void interrupt(SessionState session, WebSocketSession ws, String reason) {
        interruptWithHooks(session, ws, reason);
    }

    /**
     * 执行 VAD、送入 ASR、推进 turn 状态机，并响应产生的事件。
     */
    private void processAudio(SessionState session, WebSocketSession ws, byte[] pcm) {
        session.runWithAudioProcessingLock(() -> processAudioLocked(session, ws, pcm));
    }

    /**
     * 同一 session 内串行处理音频块，保护流式 ASR 和 turn 状态机的时间顺序。
     */
    private void processAudioLocked(SessionState session, WebSocketSession ws, byte[] pcm) {
        long turnId = session.currentTurnId() == 0 ? 1 : session.currentTurnId();
        VadDecision vad = vadEngine.detect(turnId, pcm);
        if (session.agentSpeaking() && vad.speech()) {
            long newTurnId = interruptWithHooks(session, ws, "barge_in");
            vad = new VadDecision(newTurnId, vad.speechProbability(), vad.speech(), vad.audioAt());
        }
        if (!vad.speech() && session.currentTurnId() == 0) {
            return;
        }
        if (vad.speech() && (session.currentTurnId() == 0 || session.lifecycleState() == TurnLifecycleState.IDLE)) {
            session.nextTurnId();
        }
        long activeTurnId = session.currentTurnId();
        session.activeAsrTurnId(activeTurnId);
        Optional<AsrUpdate> asrUpdate = session.asrAdapter().acceptAudio(activeTurnId, pcm);
        asrUpdate.ifPresent(update -> publishAsrPartial(session, ws, update));

        List<TurnEvent> events = session.turnManager().onAudio(session, new VadDecision(activeTurnId, vad.speechProbability(), vad.speech(), vad.audioAt()), asrUpdate, Instant.now());
        for (TurnEvent event : events) {
            if (event.interrupted()) {
                interruptWithHooks(session, ws, event.reason());
            } else if (event.committed()) {
                publishState(session, ws, event.state(), event.reason());
                AsrUpdate fin = session.asrAdapter().commitTurn(event.turnId());
                publishAsrFinal(session, ws, fin);
                startLlmAfterCommit(session, ws, fin);
            } else {
                publishState(session, ws, event.state(), event.reason());
            }
        }
    }

    /**
     * 仅在当前已提交 turn 的 ASR 最终结果存在后启动 LLM。
     */
    private void startLlmAfterCommit(SessionState session, WebSocketSession ws, AsrUpdate fin) {
        if (!fin.fin() || !session.isCurrentTurn(fin.turnId())) {
            return;
        }
        session.finalTranscript(fin.transcript());
        session.lifecycleState(TurnLifecycleState.AGENT_THINKING);
        session.activeLlmTurnId(fin.turnId());
        LlmRequest request = new LlmRequest(session.sessionId(), fin.turnId(), fin.transcript());
        hooks.forEach(hook -> hook.beforeLlm(session, ws, request));
        dispatcher.send(ws, AgentEvent.of(EventType.agent_thinking, session.sessionId(), fin.turnId(), new AgentThinkingPayload(fin.transcript())));
        CompletableFuture.runAsync(() -> {
            AtomicInteger ttsSeq = new AtomicInteger();
            llmOrchestrator.stream(request, chunk -> handleLlmChunk(session, ws, chunk, ttsSeq));
        }, agentTaskExecutor);
    }

    /**
     * 发布 LLM 文本，并立即将其合成为 TTS 音频块。
     */
    private void handleLlmChunk(SessionState session, WebSocketSession ws, LlmChunk chunk, AtomicInteger ttsSeq) {
        if (!session.isCurrentTurn(chunk.turnId())) {
            return;
        }
        hooks.forEach(hook -> hook.onLlmChunk(session, ws, chunk));
        dispatcher.send(ws, AgentEvent.of(EventType.agent_text_chunk, session.sessionId(), chunk.turnId(),
                new AgentTextChunkPayload(chunk.seq(), chunk.text(), chunk.last())));
        session.lifecycleState(TurnLifecycleState.AGENT_SPEAKING);
        session.agentSpeaking(true);
        session.activeTtsTurnId(chunk.turnId());
        List<TtsChunk> ttsChunks = ttsOrchestrator.synthesize(chunk.turnId(), ttsSeq.get(), chunk.text(), chunk.last());
        ttsSeq.addAndGet(ttsChunks.size());
        for (TtsChunk ttsChunk : ttsChunks) {
            publishTts(session, ws, ttsChunk);
        }
        if (chunk.last() && session.isCurrentTurn(chunk.turnId())) {
            session.agentSpeaking(false);
            session.lifecycleState(TurnLifecycleState.IDLE);
        }
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
     * 只为活跃 turn 发送 ASR 局部结果。
     */
    private void publishAsrPartial(SessionState session, WebSocketSession ws, AsrUpdate update) {
        if (!session.isCurrentTurn(update.turnId())) {
            return;
        }
        session.partialTranscript(update.transcript());
        dispatcher.send(ws, AgentEvent.of(EventType.asr_partial, session.sessionId(), update.turnId(), new TextPayload(update.transcript())));
    }

    /**
     * 只为活跃 turn 发送 ASR 最终结果。
     */
    private void publishAsrFinal(SessionState session, WebSocketSession ws, AsrUpdate update) {
        publishAsrFinal(session, ws, update, "audio");
    }

    /**
     * 发送用户最终文本。音频输入的 source 为 audio，直接文本输入的 source 为 text。
     */
    private void publishAsrFinal(SessionState session, WebSocketSession ws, AsrUpdate update, String source) {
        if (!session.isCurrentTurn(update.turnId())) {
            return;
        }
        session.finalTranscript(update.transcript());
        hooks.forEach(hook -> hook.onTurnCommitted(session, ws, update, source));
        dispatcher.send(ws, AgentEvent.of(EventType.asr_final, session.sessionId(), update.turnId(), new AsrFinalPayload(update.transcript(), source)));
    }

    /**
     * 使用会话的当前 turn 发布状态迁移事件。
     */
    private void publishState(SessionState session, WebSocketSession ws, TurnLifecycleState state, String reason) {
        dispatcher.send(ws, AgentEvent.of(EventType.state_changed, session.sessionId(), session.currentTurnId(), new StateChangedPayload(state.name(), reason)));
    }

    /**
     * 所有打断入口统一经过这里，确保业务 hook 能观察到新 turnId。
     */
    private long interruptWithHooks(SessionState session, WebSocketSession ws, String reason) {
        long newTurnId = interruptionManager.interrupt(session, ws, reason);
        hooks.forEach(hook -> hook.onInterrupted(session, ws, newTurnId, reason));
        return newTurnId;
    }
}
