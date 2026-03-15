package com.trading.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SnowflakeIdGeneratorTest {
    @Test
    void shouldGenerateUniqueIds() {
        final SnowflakeIdGenerator gen = new SnowflakeIdGenerator(0);
        final int count = 100_000;
        final Set<Long> ids = new HashSet<>(count);

        for (int i = 0; i < count; i++) {
            assertTrue(ids.add(gen.nextId()), "Duplicate ID detected at iteration " + i);
        }
    }

    @Test
    void shouldGenerateMonotonicallyIncreasingIds() {
        final SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1);
        long prev = gen.nextId();
        for (int i = 0; i < 10_000; i++) {
            long curr = gen.nextId();
            assertTrue(curr > prev, "ID not monotonically increasing: prev=" + prev + ", curr=" + curr);
            prev = curr;
        }
    }

    @Test
    void shouldGenerateUniqueIdsAcrossNodes() {
        final SnowflakeIdGenerator gen0 = new SnowflakeIdGenerator(0);
        final SnowflakeIdGenerator gen1 = new SnowflakeIdGenerator(1);
        // 同一时刻不同节点生成的 ID 不应冲突
        assertNotEquals(gen0.nextId(), gen1.nextId());
    }
}
