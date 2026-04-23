package io.github.kongweiguang.voice.agent.model.payload;

/**
 * TTS 音频片段 payload，音频内容使用 base64 承载。
 *
 * @param seq         当前音频片段在本轮 TTS 输出中的序号，从 0 开始递增
 * @param isLast      是否为本轮 TTS 输出的最后一个音频片段
 * @param text        生成当前音频片段时对应的文本内容
 * @param audioBase64 当前音频片段的 base64 编码内容
 * @author kongweiguang
 */
public record TtsAudioChunkPayload(Integer seq,
                                   Boolean isLast,
                                   String text,
                                   String audioBase64) implements AgentEventPayload {
}
