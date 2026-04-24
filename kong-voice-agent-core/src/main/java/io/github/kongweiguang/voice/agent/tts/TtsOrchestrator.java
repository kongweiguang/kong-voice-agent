package io.github.kongweiguang.voice.agent.tts;

import java.util.List;
import java.util.function.Consumer;

/**
 * 文本转语音的可替换边界，采用 Strategy/Adapter 模式支持模拟或真实合成。
 *
 * @author kongweiguang
 */
public interface TtsOrchestrator {
    /**
     * 将一个 LLM 文本片段合成为一个或多个有序音频块。
     */
    List<TtsChunk> synthesize(String turnId, Integer startSeq, String text, Boolean lastTextChunk);

    /**
     * 将一个 LLM 文本片段合成为流式音频块；默认实现复用非流式接口，兼容已有业务 Bean。
     *
     * <p>实现必须在方法返回前完成本次文本片段的所有回调，或通过同步异常暴露失败。
     * 如果底层 SDK 使用异步订阅，应在该方法内等待订阅完成，确保流水线能正确收口错误和状态。</p>
     */
    default void synthesizeStreaming(String turnId, Integer startSeq, String text, Boolean lastTextChunk,
                                     Consumer<TtsChunk> chunkConsumer) {
        synthesize(turnId, startSeq, text, lastTextChunk).forEach(chunkConsumer);
    }

    /**
     * 取消指定 turn 的 TTS 累计状态。默认无操作，真实实现可释放待合成文本或远端流资源。
     */
    default void cancelTurn(String turnId) {
    }
}
