package io.github.kongweiguang.voice.agent.service;

import io.github.kongweiguang.voice.agent.asr.AsrUpdate;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
import io.github.kongweiguang.voice.agent.eou.EouContext;
import io.github.kongweiguang.voice.agent.eou.EouDetector;
import io.github.kongweiguang.voice.agent.eou.EouPrediction;
import io.github.kongweiguang.voice.agent.hook.VoicePipelineHook;
import io.github.kongweiguang.voice.agent.llm.LlmOrchestrator;
import io.github.kongweiguang.voice.agent.model.AgentEvent;
import io.github.kongweiguang.voice.agent.model.EventType;
import io.github.kongweiguang.voice.agent.model.payload.AsrFinalPayload;
import io.github.kongweiguang.voice.agent.model.payload.StateChangedPayload;
import io.github.kongweiguang.voice.agent.model.payload.TextPayload;
import io.github.kongweiguang.voice.agent.playback.InterruptionManager;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.session.TurnLifecycleState;
import io.github.kongweiguang.voice.agent.tts.TtsOrchestrator;
import io.github.kongweiguang.voice.agent.turn.TurnEvent;
import io.github.kongweiguang.voice.agent.vad.VadDecision;
import io.github.kongweiguang.voice.agent.vad.VadEngine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 编排音频接入、ASR、turn 提交、LLM、TTS 和打断流程，作为 Facade
 * 隐藏多个策略、工厂、hook 和状态机协作细节。
 *
 * @author kongweiguang
 */
@Service
public class VoicePipelineService {
    /**
     * 语音活动检测器，负责把 PCM 转为说话概率。
     */
    private final VadEngine vadEngine;

    /**
     * EOU 判断器，真实业务可通过 Bean 覆盖。
     */
    private final EouDetector eouDetector;

    /**
     * EOU 端点等待窗口配置。
     */
    private final EouConfig eouConfig;

    /**
     * turn 提交后的 Agent 响应编排器。
     */
    private final AgentResponseOrchestrator agentResponseOrchestrator;

    /**
     * WebSocket 下行事件发送器。
     */
    private final PlaybackDispatcher dispatcher;

    /**
     * 统一处理主动打断和插话打断的管理器。
     */
    private final InterruptionManager interruptionManager;

    /**
     * 业务扩展 hook，按注册顺序观察关键流水线节点。
     */
    private final List<VoicePipelineHook> hooks;

    /**
     * 音频处理虚拟线程执行器。
     */
    private final Executor audioTaskExecutor;

    /**
     * 创建语音流水线门面，显式注入执行器以保持异步边界和线程职责清晰。
     */
    public VoicePipelineService(VadEngine vadEngine,
                                EouDetector eouDetector,
                                EouConfig eouConfig,
                                LlmOrchestrator llmOrchestrator,
                                TtsOrchestrator ttsOrchestrator,
                                PlaybackDispatcher dispatcher,
                                InterruptionManager interruptionManager,
                                @Qualifier("audioTaskExecutor") Executor audioTaskExecutor,
                                @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
                                List<VoicePipelineHook> hooks) {
        this.vadEngine = vadEngine;
        this.eouDetector = eouDetector;
        this.eouConfig = eouConfig;
        this.dispatcher = dispatcher;
        this.interruptionManager = interruptionManager;
        this.audioTaskExecutor = audioTaskExecutor;
        this.hooks = hooks;
        this.agentResponseOrchestrator = new AgentResponseOrchestrator(llmOrchestrator, ttsOrchestrator, dispatcher, agentTaskExecutor, hooks);
    }

    /**
     * 接收 WebSocket 线程送来的 PCM，并将耗时工作调度到 IO 线程之外。
     */
    public void acceptAudio(SessionState session, WebSocketSession ws, byte[] pcm) {
        session.lastAudioAt(Instant.now());
        // 原始音频先进入会话级缓冲，后续可用于调试、回放或接入更复杂的端点策略。
        session.pcmRingBuffer().write(pcm);
        session.preRollBuffer().write(pcm);
        hooks.forEach(hook -> hook.onAudioReceived(session, ws, pcm));
        // VAD、ASR 和状态机推进可能有模型或远端调用，必须离开 WebSocket IO 线程执行。
        CompletableFuture.runAsync(() -> processAudio(session, ws, pcm), audioTaskExecutor);
    }

    /**
     * 客户端强制结束音频：提交 ASR 最终结果后再启动 LLM。
     */
    public void commitAudioEnd(SessionState session, WebSocketSession ws) {
        String turnId = session.currentTurnId();
        if (!session.isCurrentTurn(turnId)) {
            return;
        }
        // audio_end 可能触发远端 ASR commit，必须离开 WebSocket 文本消息线程并复用音频串行锁。
        CompletableFuture.runAsync(
                () -> session.runWithAudioProcessingLock(() -> commitAudioTurn(session, ws, turnId, "audio_end")),
                audioTaskExecutor
        );
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
        String turnId = Boolean.TRUE.equals(session.agentSpeaking())
                ? interruptWithHooks(session, ws, "client_text")
                : session.nextTurnId();
        // 文本输入已经是完整用户 turn，因此直接进入 committed 状态，不经过 VAD/ASR/EOU。
        session.lastSpeechAt(Instant.now());
        session.lifecycleState(TurnLifecycleState.USER_TURN_COMMITTED);
        publishState(session, ws, turnId, TurnLifecycleState.USER_TURN_COMMITTED, "text");
        AsrUpdate fin = AsrUpdate.finalUpdate(turnId, normalized);
        publishAsrFinal(session, ws, fin, "text");
        agentResponseOrchestrator.startAfterCommit(session, ws, fin);
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
        String turnId = session.currentTurnId();
        // 先用当前 turnId 做 VAD；空闲状态下 turnId 可能为空，真正检测到人声后才会创建新 turn。
        VadDecision vad = vadEngine.detect(turnId, pcm);
        if (Boolean.TRUE.equals(session.agentSpeaking()) && Boolean.TRUE.equals(vad.speech())) {
            // Agent 播报过程中检测到用户说话，按插话处理并把当前音频块归入新的用户 turn。
            String newTurnId = interruptWithHooks(session, ws, "barge_in");
            vad = new VadDecision(newTurnId, vad.speechProbability(), vad.speech(), vad.audioAt());
        }
        if (!Boolean.TRUE.equals(vad.speech()) && !session.hasCurrentTurn()) {
            // 空闲阶段的静音帧没有业务意义，避免送入 ASR 或推进状态机。
            return;
        }
        if (Boolean.TRUE.equals(vad.speech()) && (!session.hasCurrentTurn() || session.lifecycleState() == TurnLifecycleState.IDLE)) {
            // 用户从空闲状态开始说话时创建新的 turn，后续 ASR、EOU、LLM 和 TTS 都围绕该 turn 隔离。
            session.nextTurnId();
        }
        String activeTurnId = session.currentTurnId();
        session.activeAsrTurnId(activeTurnId);
        // 每个有效音频块都会进入 ASR；真流式 ASR 可返回 partial，同步 HTTP ASR 通常只缓存并返回空。
        Optional<AsrUpdate> asrUpdate = session.asrAdapter().acceptAudio(activeTurnId, pcm);
        asrUpdate.ifPresent(update -> publishAsrPartial(session, ws, update));

        Instant now = Instant.now();
        // EOU 只在静音候选且已有 ASR 文本时参与判断，用于区分短暂停顿和真正说完。
        Optional<EouPrediction> eouPrediction = maybePredictEou(session, activeTurnId, vad, now);
        // TurnManager 统一消费 VAD、ASR partial、EOU 和时间信息，产出状态迁移、提交或打断事件。
        List<TurnEvent> events = session.turnManager()
                .onAudio(session,
                        new VadDecision(activeTurnId,
                                vad.speechProbability(),
                                vad.speech(),
                                vad.audioAt()),
                        asrUpdate,
                        eouPrediction,
                        now);

        for (TurnEvent event : events) {
            if (Boolean.TRUE.equals(event.interrupted())) {
                interruptWithHooks(session, ws, event.reason());
            } else if (Boolean.TRUE.equals(event.committed())) {
                // 只有 committed 事件才会取 ASR final，并以该最终文本作为 LLM/TTS 的启动边界。
                commitAudioTurn(session, ws, event.turnId(), event.reason());
            } else {
                // 非提交事件只同步生命周期状态给前端，例如说话中、静音候选或 EOU 等待。
                publishState(session, ws, event.turnId(), event.state(), event.reason());
            }
        }
    }

    /**
     * 提交音频 turn，并把 ASR final 交给 Agent 响应编排器。
     */
    private void commitAudioTurn(SessionState session, WebSocketSession ws, String turnId, String reason) {
        if (!session.isCurrentTurn(turnId)) {
            return;
        }
        session.lifecycleState(TurnLifecycleState.USER_TURN_COMMITTED);
        publishState(session, ws, turnId, TurnLifecycleState.USER_TURN_COMMITTED, reason);
        try {
            // audio_end 或 endpoint committed 都是提交边界，只有这里允许获取 ASR final。
            AsrUpdate fin = session.asrAdapter().commitTurn(turnId);
            publishAsrFinal(session, ws, fin);
            agentResponseOrchestrator.startAfterCommit(session, ws, fin);
        } catch (Exception ex) {
            agentResponseOrchestrator.publishFailure(session, ws, turnId, "asr_failed", ex);
        }
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
     * 在静音候选阶段调用 EOU，且同一文本只推理一次，避免每个音频帧重复消耗模型。
     */
    private Optional<EouPrediction> maybePredictEou(SessionState session, String turnId, VadDecision vad, Instant now) {
        if (!Boolean.TRUE.equals(eouConfig.enabled()) || Boolean.TRUE.equals(vad.speech()) || session.lastSpeechAt() == null || !session.isCurrentTurn(turnId)) {
            return Optional.empty();
        }
        long silenceMs = Duration.between(session.lastSpeechAt(), now).toMillis();
        if (silenceMs < eouConfig.minSilenceMs()) {
            return Optional.empty();
        }
        String transcript = session.partialTranscript();
        if (transcript == null || transcript.isBlank()) {
            return Optional.empty();
        }
        if (session.sameLastEouTranscript(transcript)) {
            return Optional.of(session.lastEouPrediction());
        }
        EouContext context = new EouContext(
                session.sessionId(),
                turnId,
                transcript,
                eouConfig.language(),
                silenceMs
        );
        EouPrediction prediction = eouDetector.predict(context);
        session.eouPrediction(transcript, prediction);
        return Optional.of(prediction);
    }

    /**
     * 使用事件所属 turnId 发布状态迁移事件，避免并发切换 turn 后带上新的 currentTurnId。
     */
    private void publishState(SessionState session, WebSocketSession ws, String turnId, TurnLifecycleState state, String reason) {
        dispatcher.send(ws, AgentEvent.of(EventType.state_changed, session.sessionId(), turnId, new StateChangedPayload(state.name(), reason)));
    }

    /**
     * 所有打断入口统一经过这里，确保业务 hook 能观察到新 turnId。
     */
    private String interruptWithHooks(SessionState session, WebSocketSession ws, String reason) {
        String newTurnId = interruptionManager.interrupt(session, ws, reason);
        if (newTurnId != null) {
            hooks.forEach(hook -> hook.onInterrupted(session, ws, newTurnId, reason));
        }
        return newTurnId;
    }
}
