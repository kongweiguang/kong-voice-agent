package io.github.kongweiguang.voice.agent.session;

import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapter;
import io.github.kongweiguang.voice.agent.asr.StreamingAsrAdapterFactory;
import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.audio.CircularByteBuffer;
import io.github.kongweiguang.voice.agent.audio.PreRollBuffer;
import io.github.kongweiguang.voice.agent.turn.EndpointingPolicy;
import io.github.kongweiguang.voice.agent.turn.TurnManager;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 每个 WebSocket 连接独有的可变状态。所有异步流水线阶段发布结果前，
 * 都通过该对象检查自己的 turn 是否仍是当前 turn。
 *
 * @author kongweiguang
 */
@Getter
@Setter
@Accessors(fluent = true)
public class SessionState {
    /**
     * 服务端生成的语音会话标识。
     */
    private final String sessionId;

    /**
     * 当前活跃 turnId，0 表示尚未创建用户 turn。
     */
    @Getter(AccessLevel.NONE)
    private final AtomicLong currentTurnId = new AtomicLong();

    /**
     * 保存最近一段 PCM 音频，便于后续调试或扩展回放。
     */
    private final CircularByteBuffer pcmRingBuffer;

    /**
     * 保存用户开口前的预滚音频。
     */
    private final PreRollBuffer preRollBuffer;

    /**
     * 当前会话独占的流式 ASR 适配器。
     */
    private final StreamingAsrAdapter asrAdapter;

    /**
     * 当前会话独占的 turn 状态机。
     */
    private final TurnManager turnManager;

    /**
     * 已失效 turn 集合，用于异步回调发布前的过期判断。
     */
    private final Set<Long> invalidTurns = ConcurrentHashMap.newKeySet();
    /**
     * turn 切换会同时重置多项状态，使用显式锁保证这些变更整体可见。
     */
    private final ReentrantLock turnLock = new ReentrantLock();
    /**
     * 同一连接内的音频块必须按顺序推进 VAD/ASR/TurnManager，避免虚拟线程并发打乱状态机。
     */
    private final ReentrantLock audioProcessingLock = new ReentrantLock();

    /**
     * 当前 turn 生命周期状态。
     */
    private volatile TurnLifecycleState lifecycleState = TurnLifecycleState.IDLE;

    /**
     * 当前 turn 的 ASR 局部转写。
     */
    @Setter(AccessLevel.NONE)
    private volatile String partialTranscript = "";

    /**
     * 当前 turn 的最终用户文本。
     */
    @Setter(AccessLevel.NONE)
    private volatile String finalTranscript = "";

    /**
     * 最近一次收到音频帧的时间。
     */
    private volatile Instant lastAudioAt;

    /**
     * 最近一次检测到用户说话的时间。
     */
    private volatile Instant lastSpeechAt;

    /**
     * 当前活跃 ASR turnId，-1 表示未开始。
     */
    private volatile long activeAsrTurnId = -1;

    /**
     * 当前活跃 LLM turnId，-1 表示未开始。
     */
    private volatile long activeLlmTurnId = -1;

    /**
     * 当前活跃 TTS turnId，-1 表示未开始。
     */
    private volatile long activeTtsTurnId = -1;

    /**
     * Agent 是否正在播报，插话打断依赖该标记。
     */
    private volatile boolean agentSpeaking;

    /**
     * 当前 turn 是否已进入打断流程。
     */
    private volatile boolean interrupted;

    /**
     * 创建一个 WebSocket 连接独占的运行态。
     */
    public SessionState(String sessionId, AudioFormatSpec format, StreamingAsrAdapterFactory asrAdapterFactory) {
        this.sessionId = sessionId;
        this.pcmRingBuffer = new CircularByteBuffer(format.bytesForMs(30_000));
        this.preRollBuffer = new PreRollBuffer(format, 400);
        this.asrAdapter = asrAdapterFactory.create(sessionId, format);
        this.turnManager = new TurnManager(new EndpointingPolicy());
    }

    /**
     * 启动新的用户 turn，并在旧 turn 存在时将其失效。
     */
    public long nextTurnId() {
        turnLock.lock();
        try {
            long previous = currentTurnId.get();
            if (previous > 0) {
                invalidTurns.add(previous);
            }
            partialTranscript = "";
            finalTranscript = "";
            interrupted = false;
            return currentTurnId.incrementAndGet();
        } finally {
            turnLock.unlock();
        }
    }

    /**
     * 保护每个异步回调，避免过期 ASR/LLM/TTS 输出泄漏。
     */
    public boolean isCurrentTurn(long turnId) {
        return currentTurnId.get() == turnId && !invalidTurns.contains(turnId);
    }

    /**
     * 标记某个 turn 不再允许发布下游事件。
     */
    public void invalidateTurn(long turnId) {
        invalidTurns.add(turnId);
    }

    /**
     * WebSocket 断开时释放会话内部状态。
     */
    public void clear() {
        invalidateTurn(currentTurnId.get());
        pcmRingBuffer.clear();
        preRollBuffer.clear();
        partialTranscript = "";
        finalTranscript = "";
        lifecycleState = TurnLifecycleState.IDLE;
        agentSpeaking = false;
        interrupted = false;
        asrAdapter.close();
    }

    /**
     * 返回当前会话标识；Lombok fluent getter 对 final 字段不覆盖这里的显式语义。
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * 返回当前活跃 turnId。
     */
    public long currentTurnId() {
        return currentTurnId.get();
    }

    /**
     * 更新当前 turn 的 ASR 局部转写，null 会归一化为空字符串。
     */
    public void partialTranscript(String partialTranscript) {
        this.partialTranscript = partialTranscript == null ? "" : partialTranscript;
    }

    /**
     * 更新当前 turn 的最终用户文本，null 会归一化为空字符串。
     */
    public void finalTranscript(String finalTranscript) {
        this.finalTranscript = finalTranscript == null ? "" : finalTranscript;
    }

    /**
     * 串行执行同一会话内的音频处理任务。
     */
    public void runWithAudioProcessingLock(Runnable task) {
        audioProcessingLock.lock();
        try {
            task.run();
        } finally {
            audioProcessingLock.unlock();
        }
    }
}
