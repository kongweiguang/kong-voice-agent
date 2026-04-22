package io.github.kongweiguang.voice.agent.audio;

import java.time.Instant;

/**
 * 原始音频帧，携带到达时处于活跃状态的 turn。
 *
 * @author kongweiguang
 */
public record AudioChunk(
        /**
         * 音频帧到达时对应的活跃 turnId。
         */
        String turnId,

        /**
         * 客户端上传的原始 PCM 字节数据。
         */
        byte[] pcm,

        /**
         * 服务端接收到该音频帧的时间。
         */
        Instant receivedAt) {
}
