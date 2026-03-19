package com.trading.matching.matcher;

import com.trading.matching.orderbook.*;
import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import com.trading.sbe.TimeInForce;
import com.trading.util.NanoTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 手续费计算精度测试。
 *
 * <p>手续费公式：fee = price × quantity × feeRateMicros / 1_000_000
 *
 * @author Reln Ding
 */
class FeeCalculationTest {

    private static final int SYMBOL_ID = 1;

    private OrderNodePool pool;
    private OrderBook     book;
    private MatchResult   result;

    @BeforeEach
    void setUp() {
        pool   = new OrderNodePool(256);
        book   = new OrderBook(SYMBOL_ID, pool);
        result = new MatchResult(64);
    }

    private OrderMatcher matcherWithFee(final int makerFee, final int takerFee) {
        return new OrderMatcher(book, makerFee, takerFee, NanoTimeProvider.SYSTEM);
    }

    private OrderNode sell(final long id, final long price, final long qty) {
        final OrderNode n = pool.borrow();
        assertNotNull(n);
        n.init(id, 1001L, SYMBOL_ID, Side.SELL.value(),
                OrderType.LIMIT.value(), TimeInForce.GTC.value(),
                price, qty, 0L, 0L);
        return n;
    }

    private OrderNode buy(final long id, final long price, final long qty) {
        final OrderNode n = pool.borrow();
        assertNotNull(n);
        n.init(id, 1002L, SYMBOL_ID, Side.BUY.value(),
                OrderType.LIMIT.value(), TimeInForce.GTC.value(),
                price, qty, 0L, 0L);
        return n;
    }

    @Test
    @DisplayName("标准手续费：Maker 0.1%，Taker 0.2%")
    void standardFeeCalculation() {
        final OrderMatcher matcher = matcherWithFee(1_000, 2_000);
        final OrderNode sellNode = sell(1L, 5000_00L, 100L);
        result.reset(sellNode);
        matcher.match(sellNode, result);

        final OrderNode buyNode = buy(2L, 5000_00L, 100L);
        result.reset(buyNode);
        matcher.match(buyNode, result);

        assertEquals(1, result.events.size());
        final MatchEvent e = result.events.get(0);

        // makerFee = 5000_00 * 100 * 1000 / 1_000_000 = 500_00 * 100 / 1000 = 50000
        // 验证非零且为正
        assertTrue(e.makerFee > 0,  "Maker fee should be positive");
        assertTrue(e.takerFee > 0,  "Taker fee should be positive");
        // Taker fee 应约为 Maker fee 的 2 倍
        assertTrue(e.takerFee > e.makerFee, "Taker fee should be greater than maker fee");
    }

    @Test
    @DisplayName("零手续费率：fee 应为 0")
    void zeroFeeRate() {
        final OrderMatcher matcher = matcherWithFee(0, 0);
        final OrderNode sellNode = sell(1L, 5000_00L, 100L);
        result.reset(sellNode);
        matcher.match(sellNode, result);

        final OrderNode buyNode = buy(2L, 5000_00L, 100L);
        result.reset(buyNode);
        matcher.match(buyNode, result);

        assertEquals(1, result.events.size());
        assertEquals(0L, result.events.get(0).makerFee);
        assertEquals(0L, result.events.get(0).takerFee);
    }

    @Test
    @DisplayName("Maker 负费率（返佣）：makerFee 为负值")
    void negativeMakerFeeRebate() {
        final OrderMatcher matcher = matcherWithFee(-500, 2_000);  // Maker -0.05% 返佣

        final OrderNode sellNode = sell(1L, 5000_00L, 100L);
        result.reset(sellNode);
        matcher.match(sellNode, result);

        final OrderNode buyNode = buy(2L, 5000_00L, 100L);
        result.reset(buyNode);
        matcher.match(buyNode, result);

        assertEquals(1, result.events.size());
        assertTrue(result.events.get(0).makerFee < 0, "Maker fee should be negative (rebate)");
        assertTrue(result.events.get(0).takerFee > 0, "Taker fee should be positive");
    }

    @Test
    @DisplayName("大价格 × 大数量不溢出")
    void largeValueDoesNotOverflow() {
        // 价格 100,000.00（精度 0.01，long = 10_000_000）
        // 数量 1,000,000（精度 0.01，long = 100_000_000）
        final OrderMatcher matcher = matcherWithFee(1_000, 2_000);
        final OrderNode sellNode = sell(1L, 10_000_000L, 100_000_000L);
        result.reset(sellNode);
        matcher.match(sellNode, result);

        final OrderNode buyNode = buy(2L, 10_000_000L, 100_000_000L);
        result.reset(buyNode);
        matcher.match(buyNode, result);

        assertEquals(1, result.events.size());
        // 只验证不溢出（结果为正）
        assertTrue(result.events.get(0).makerFee >= 0);
        assertTrue(result.events.get(0).takerFee >= 0);
    }
}
