package com.trading.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PriceUtilTest {
    @Test
    void shouldConvertDoubleToLong() {
        // 50000.25 USDT，精度 2 → 5000025L
        assertEquals(5000025L, PriceUtil.toLong(50000.25, 2));
        assertEquals(100000L, PriceUtil.toLong(1.00000, 5));  // 1 BTC，精度 5
    }

    @Test
    void shouldConvertLongToDouble() {
        assertEquals(50000.25, PriceUtil.toDouble(5000025L, 2), 1e-10);
        assertEquals(1.0, PriceUtil.toDouble(100000L, 5), 1e-10);
    }

    @Test
    void shouldAlignToTick() {
        // tick=100 (0.01 精度下的最小单位 1)，对齐
        assertEquals(5000000L, PriceUtil.alignToTick(5000025L, 100L));
        assertEquals(5000100L, PriceUtil.alignToTick(5000100L, 100L));
    }

    @Test
    void shouldValidateTick() {
        assertTrue(PriceUtil.isValidTick(5000000L, 100L));
        assertFalse(PriceUtil.isValidTick(5000025L, 100L));
    }
}
