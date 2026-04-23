package io.github.kongweiguang.voice.agent.audio;

import java.time.Instant;

/**
 * 原始音频帧，携带到达时处于活跃状态的 turn。
 *
 * @param turnId     音频帧到达时对应的活跃 turnId
 * @param pcm        客户端上传的原始 PCM 字节数据
 * @param receivedAt 服务端接收到该音频帧的时间
 * @author kongweiguang
 */
public record AudioChunk(String turnId, byte[] pcm, Instant receivedAt) {
}
