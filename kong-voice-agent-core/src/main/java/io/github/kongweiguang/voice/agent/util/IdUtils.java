package io.github.kongweiguang.voice.agent.util;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 生成协议事件使用的不透明标识符。
 *
 * @author kongweiguang
 */
public final class IdUtils {
    /**
     * 用于生成不可预测会话 id 的安全随机数。
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 工具类不允许实例化。
     */
    private IdUtils() {
    }

    /**
     * 生成 WebSocket 会话 id，格式为 sess_ 加十六进制随机后缀。
     */
    public static String sessionId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return "sess_" + HexFormat.of().formatHex(bytes);
    }
}
