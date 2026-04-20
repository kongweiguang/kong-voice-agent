package io.github.kongweiguang.voice.agent.support;

import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.session.SessionState;

/**
 * 为 core 单元测试创建不依赖 app mock 的会话状态。
 *
 * @author kongweiguang
 */
public final class TestSessionStates {
    private TestSessionStates() {
    }

    public static SessionState create(String sessionId) {
        return new SessionState(sessionId, AudioFormatSpec.DEFAULT, (ignoredSessionId, format) -> new NoopStreamingAsrAdapter());
    }
}
