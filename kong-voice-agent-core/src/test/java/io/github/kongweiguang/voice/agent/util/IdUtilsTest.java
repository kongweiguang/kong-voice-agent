package io.github.kongweiguang.voice.agent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证通用 ID 工具在会话标识和雪花 ID 场景下的长期行为。
 *
 * @author kongweiguang
 */
@Tag("util")
@DisplayName("ID 工具")
class IdUtilsTest {
    /**
     * 保护随机 session id 不受雪花 ID 节点配置影响，两类 ID 的生命周期彼此独立。
     */
    @Test
    @DisplayName("雪花节点配置不影响 session id")
    void snowflakeNodeConfigDoesNotAffectSessionId() {
        String propertyName = "kong.voice-agent.snowflake.node-id";
        String previousValue = System.getProperty(propertyName);
        System.setProperty(propertyName, "not-a-number");
        try {
            assertThat(IdUtils.sessionId()).startsWith("sess_");
        } finally {
            if (previousValue == null) {
                System.clearProperty(propertyName);
            } else {
                System.setProperty(propertyName, previousValue);
            }
        }
    }

    /**
     * 保护雪花 ID 在常规连续调用时保持唯一且递增。
     */
    @Test
    @DisplayName("生成唯一递增的雪花 ID")
    void generatesUniqueIncreasingSnowflakeIds() {
        IdUtils.SnowflakeGenerator generator = IdUtils.snowflakeGenerator(1);

        long first = generator.nextId();
        long second = generator.nextId();
        long third = generator.nextId();

        assertThat(first).isPositive();
        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
    }

    /**
     * 保护同一毫秒内依靠序列号区分 ID，避免高并发下重复。
     */
    @Test
    @DisplayName("同一毫秒内使用递增序列号")
    void usesSequenceWithinSameMillisecond() {
        long fixedTimestamp = 1704067200123L;
        IdUtils.SnowflakeGenerator generator = new IdUtils.SnowflakeGenerator(7, () -> fixedTimestamp);

        long first = generator.nextId();
        long second = generator.nextId();
        long third = generator.nextId();

        assertThat(second - first).isEqualTo(1);
        assertThat(third - second).isEqualTo(1);
    }

    /**
     * 保护每毫秒序列号耗尽后等待下一毫秒，避免序列回绕造成重复 ID。
     */
    @Test
    @DisplayName("序列号耗尽后进入下一毫秒")
    void waitsForNextMillisWhenSequenceOverflows() {
        AtomicInteger calls = new AtomicInteger();
        IdUtils.SnowflakeGenerator generator = new IdUtils.SnowflakeGenerator(3, () -> {
            int currentCall = calls.incrementAndGet();
            if (currentCall <= 4097) {
                return 1704067200001L;
            }
            return 1704067200002L;
        });
        Set<Long> ids = new HashSet<>();

        for (int i = 0; i < 4097; i++) {
            ids.add(generator.nextId());
        }

        assertThat(ids).hasSize(4097);
        assertThat(ids.stream().max(Long::compareTo)).hasValueSatisfying(max -> assertThat(max)
                .isGreaterThan(((1704067200001L - 1704067200000L) << 22)));
    }

    /**
     * 保护节点号范围，避免部署时错误配置生成与其他节点重叠的 ID 空间。
     */
    @Test
    @DisplayName("拒绝非法节点号")
    void rejectsInvalidNodeId() {
        assertThatThrownBy(() -> IdUtils.snowflakeGenerator(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("节点号");
        assertThatThrownBy(() -> IdUtils.snowflakeGenerator(1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("节点号");
    }

    /**
     * 保护时钟回拨边界，避免时间倒退时继续生成不可靠 ID。
     */
    @Test
    @DisplayName("系统时钟回拨时抛出异常")
    void rejectsClockRollback() {
        AtomicInteger calls = new AtomicInteger();
        IdUtils.SnowflakeGenerator generator = new IdUtils.SnowflakeGenerator(1, () -> {
            if (calls.incrementAndGet() == 1) {
                return 1704067201000L;
            }
            return 1704067200999L;
        });

        generator.nextId();

        assertThatThrownBy(generator::nextId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("时钟");
    }

    /**
     * 保护自定义纪元边界，避免系统时间异常时生成负数 ID。
     */
    @Test
    @DisplayName("系统时间早于自定义纪元时抛出异常")
    void rejectsTimeBeforeEpoch() {
        IdUtils.SnowflakeGenerator generator = new IdUtils.SnowflakeGenerator(1, () -> 1704067199999L);

        assertThatThrownBy(generator::nextId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自定义纪元");
    }
}
