package io.github.kongweiguang.voice.agent.media;

/**
 * 音频下行扩展点的空实现。
 *
 * @author kongweiguang
 */
final class NoopAudioEgressAdapter implements AudioEgressAdapter {
    /**
     * 进程内共享单例，避免频繁创建无状态对象。
     */
    static final NoopAudioEgressAdapter INSTANCE = new NoopAudioEgressAdapter();

    /**
     * 禁止外部创建多余实例。
     */
    private NoopAudioEgressAdapter() {
    }
}
