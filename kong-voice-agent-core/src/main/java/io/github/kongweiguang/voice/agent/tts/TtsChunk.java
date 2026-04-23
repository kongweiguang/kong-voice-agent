package io.github.kongweiguang.voice.agent.tts;

/**
 * 可通过 WebSocket 下发的合成音频块。
 *
 * @param turnId 当前音频块所属的 turnId
 * @param seq    当前音频块在本轮 TTS 输出中的序号
 * @param last   是否为本轮 TTS 输出的最后一个音频块
 * @param audio  当前 TTS 音频原始字节
 * @param text   合成当前音频块时对应的文本片段
 * @author kongweiguang
 */
public record TtsChunk(String turnId, Integer seq, Boolean last, byte[] audio, String text) {
}
