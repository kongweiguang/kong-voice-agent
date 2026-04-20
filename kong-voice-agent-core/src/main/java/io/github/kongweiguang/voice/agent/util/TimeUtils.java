package io.github.kongweiguang.voice.agent.util;

import java.time.Instant;

/**
 * 小型时钟工具，为后续时间抽象保留统一入口。
 *
 * @author kongweiguang
 */
public final class TimeUtils {
    /**
     * 工具类不允许实例化。
     */
    private TimeUtils() {
    }

    /**
     * 返回当前 UTC 时间的 epoch 毫秒。
     */
    public static long epochMillis() {
        return Instant.now().toEpochMilli();
    }
}
