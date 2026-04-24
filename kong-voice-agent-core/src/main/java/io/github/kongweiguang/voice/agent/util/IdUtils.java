package io.github.kongweiguang.voice.agent.util;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * 生成协议事件和业务流水线使用的标识符。
 *
 * @author kongweiguang
 */
public final class IdUtils {
    /**
     * 用于生成不可预测会话 id 的安全随机数。
     */
    private static final SecureRandom RANDOM = new SecureRandom();
    /**
     * 雪花算法默认节点号的系统属性名。
     */
    private static final String NODE_ID_PROPERTY = "kong.voice-agent.snowflake.node-id";
    /**
     * 雪花算法默认节点号的环境变量名。
     */
    private static final String NODE_ID_ENV = "KONG_VOICE_AGENT_SNOWFLAKE_NODE_ID";

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

    /**
     * 生成全局趋势递增的雪花 ID。
     */
    public static long snowflakeId() {
        return SnowflakeHolder.SNOWFLAKE.nextId();
    }

    /**
     * 生成全局趋势递增的雪花 ID。
     */
    public static String snowflakeIdStr() {
        return String.valueOf(snowflakeId());
    }


    /**
     * 创建指定节点号的雪花 ID 生成器，适合多实例部署时由外部配置显式分配节点。
     */
    public static SnowflakeGenerator snowflakeGenerator(long nodeId) {
        return new SnowflakeGenerator(nodeId, System::currentTimeMillis);
    }

    /**
     * 解析默认节点号；未配置时使用随机节点，避免本地单机启动必须额外配置。
     */
    private static long resolveDefaultNodeId() {
        String configuredNodeId = System.getProperty(NODE_ID_PROPERTY);
        if (configuredNodeId == null || configuredNodeId.isBlank()) {
            configuredNodeId = System.getenv(NODE_ID_ENV);
        }
        if (configuredNodeId == null || configuredNodeId.isBlank()) {
            return RANDOM.nextLong(SnowflakeGenerator.MAX_NODE_ID + 1);
        }
        try {
            return Long.parseLong(configuredNodeId.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("雪花 ID 节点号配置必须是 0 到 "
                                               + SnowflakeGenerator.MAX_NODE_ID + " 之间的整数", ex);
        }
    }

    /**
     * 延迟创建默认雪花生成器，避免雪花节点配置错误影响随机 session id 生成。
     *
     * @author kongweiguang
     */
    private static final class SnowflakeHolder {
        /**
         * 进程内默认雪花 ID 生成器。
         */
        private static final SnowflakeGenerator SNOWFLAKE = new SnowflakeGenerator(resolveDefaultNodeId(), System::currentTimeMillis);

        /**
         * 工具持有类不允许实例化。
         */
        private SnowflakeHolder() {
        }
    }

    /**
     * 基于毫秒时间戳、节点号和同毫秒序列号生成 64 位正整数雪花 ID。
     *
     * @author kongweiguang
     */
    public static final class SnowflakeGenerator {
        /**
         * 自定义纪元，减少时间戳位数占用并把可用时间窗口留给项目生命周期。
         */
        private static final long EPOCH_MILLIS = 1704067200000L;
        /**
         * 节点号位数，支持 1024 个节点。
         */
        private static final int NODE_ID_BITS = 10;
        /**
         * 同一毫秒内序列号位数，每节点每毫秒最多生成 4096 个 ID。
         */
        private static final int SEQUENCE_BITS = 12;
        /**
         * 序列号最大值。
         */
        private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
        /**
         * 节点号最大值。
         */
        private static final long MAX_NODE_ID = (1L << NODE_ID_BITS) - 1;
        /**
         * 节点号左移位数。
         */
        private static final int NODE_ID_SHIFT = SEQUENCE_BITS;
        /**
         * 时间戳左移位数。
         */
        private static final int TIMESTAMP_SHIFT = NODE_ID_BITS + SEQUENCE_BITS;
        /**
         * 生成器状态锁，保护同毫秒序列号递增和时钟回拨检查。
         */
        private final ReentrantLock lock = new ReentrantLock();
        /**
         * 当前节点号。
         */
        private final long nodeId;
        /**
         * 当前时间毫秒供应器，允许生成器按调用方提供的时间源工作。
         */
        private final LongSupplier currentTimeMillis;
        /**
         * 上一次生成 ID 使用的时间戳。
         */
        private long lastTimestamp = -1;
        /**
         * 当前毫秒内已经分配的序列号。
         */
        private long sequence;

        /**
         * 创建指定节点号的雪花 ID 生成器。
         */
        SnowflakeGenerator(long nodeId, LongSupplier currentTimeMillis) {
            if (nodeId < 0 || nodeId > MAX_NODE_ID) {
                throw new IllegalArgumentException("雪花 ID 节点号必须在 0 到 " + MAX_NODE_ID + " 之间");
            }
            this.nodeId = nodeId;
            this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
        }

        /**
         * 生成下一个雪花 ID。
         */
        public long nextId() {
            lock.lock();
            try {
                long timestamp = currentTimeMillis.getAsLong();
                if (timestamp < lastTimestamp) {
                    throw new IllegalStateException("系统时钟发生回拨，无法生成雪花 ID");
                }
                if (timestamp < EPOCH_MILLIS) {
                    throw new IllegalStateException("系统时间早于雪花 ID 自定义纪元，无法生成雪花 ID");
                }
                if (timestamp == lastTimestamp) {
                    sequence = (sequence + 1) & MAX_SEQUENCE;
                    if (sequence == 0) {
                        timestamp = waitUntilNextMillis(lastTimestamp);
                    }
                } else {
                    sequence = 0;
                }
                lastTimestamp = timestamp;
                return ((timestamp - EPOCH_MILLIS) << TIMESTAMP_SHIFT)
                       | (nodeId << NODE_ID_SHIFT)
                       | sequence;
            } finally {
                lock.unlock();
            }
        }

        /**
         * 等待进入下一毫秒，避免同毫秒序列号溢出后生成重复 ID。
         */
        private long waitUntilNextMillis(long previousTimestamp) {
            long timestamp = currentTimeMillis.getAsLong();
            while (timestamp <= previousTimestamp) {
                Thread.onSpinWait();
                timestamp = currentTimeMillis.getAsLong();
            }
            return timestamp;
        }
    }
}
