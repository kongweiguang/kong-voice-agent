package io.github.kongweiguang.voice.agent.turn;

import io.github.kongweiguang.voice.agent.session.SessionState;
import io.github.kongweiguang.voice.agent.tts.TtsOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 统一处理 turn 失效后的资源释放，避免 ASR/TTS 缓存散落在各自入口中无法清理。
 *
 * @author kongweiguang
 */
@Component
@RequiredArgsConstructor
public class TurnCancellationCoordinator {
    /**
     * TTS 编排器可能按 turn 累计待合成文本，取消时需要显式释放。
     */
    private final TtsOrchestrator ttsOrchestrator;

    /**
     * 失效指定 turn，并通知当前会话 ASR 与全局 TTS 释放该 turn 的缓存。
     */
    public void cancel(SessionState session, String turnId) {
        if (turnId == null) {
            return;
        }
        session.invalidateTurn(turnId);
        session.asrAdapter().cancelTurn(turnId);
        ttsOrchestrator.cancelTurn(turnId);
    }
}
