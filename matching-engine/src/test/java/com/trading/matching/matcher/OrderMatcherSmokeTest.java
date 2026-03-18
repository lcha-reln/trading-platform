package com.trading.matching.matcher;

import com.trading.matching.orderbook.MatchEvent;
import com.trading.matching.orderbook.MatchResult;
import com.trading.matching.orderbook.OrderBook;
import com.trading.matching.orderbook.OrderNode;
import com.trading.matching.orderbook.OrderNodePool;
import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import com.trading.sbe.TimeInForce;
import com.trading.util.NanoTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OrderMatcherSmokeTest {
    private static final int SYMBOL_ID = 1;
    private static final int MAKER_FEE_MICROS = 1_000;   // 0.1%
    private static final int TAKER_FEE_MICROS = 2_000;   // 0.2%

    private OrderNodePool nodePool;
    private OrderBook book;
    private OrderMatcher matcher;
    private MatchResult result;

    @BeforeEach
    void setUp() {
        nodePool = new OrderNodePool(1024);
        book = new OrderBook(SYMBOL_ID, nodePool);
        matcher = new OrderMatcher(book, MAKER_FEE_MICROS, TAKER_FEE_MICROS,
                NanoTimeProvider.SYSTEM);
        result = new MatchResult(256);
    }

    // ---- 辅助方法 ----

    private OrderNode makeLimitOrder(final long orderId, final long accountId,
                                     final byte side, final long price, final long qty) {
        final OrderNode n = nodePool.borrow();
        assertNotNull(n);
        n.init(orderId, accountId, SYMBOL_ID, side,
                OrderType.LIMIT.value(), TimeInForce.GTC.value(),
                price, qty, 0L, System.nanoTime());
        return n;
    }

    private OrderNode makeOrderOfType(final long orderId, final long accountId,
                                      final byte side, final long price, final long qty,
                                      final byte orderType) {
        final OrderNode n = nodePool.borrow();
        assertNotNull(n);
        n.init(orderId, accountId, SYMBOL_ID, side,
                orderType, TimeInForce.GTC.value(),
                price, qty, 0L, System.nanoTime());
        return n;
    }

    // ================================================================
    // Limit 撮合
    // ================================================================

    @Test
    @DisplayName("Limit: 买单价格 >= 卖单价格时完全撮合")
    void limitShouldMatchWhenPriceCross() {
        // 先挂卖单
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 100L);
        result.reset(sell);
        matcher.match(sell, result);
        assertEquals(MatchResult.TakerStatus.RESTING, result.takerStatus);
        assertFalse(result.events.hasEvents());

        // 买单价格 >= 卖单价格，触发撮合
        final OrderNode buy = makeLimitOrder(2L, 1002L, Side.BUY.value(), 5000_00L, 100L);
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.FULLY_FILLED, result.takerStatus);
        assertEquals(1, result.events.size());
        final MatchEvent e = result.events.get(0);
        assertEquals(5000_00L, e.price);
        assertEquals(100L, e.quantity);
        assertEquals(1L, e.makerOrderId);
        assertEquals(2L, e.takerOrderId);
        assertTrue(e.makerFullyFilled);
        assertTrue(e.takerFullyFilled);
        assertTrue(book.isAskEmpty());
        assertTrue(book.isBidEmpty());
    }

    @Test
    @DisplayName("Limit: 买单价格 < 卖单价格时挂单等待")
    void limitShouldRestWhenNoCross() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5001_00L, 100L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode buy = makeLimitOrder(2L, 1002L, Side.BUY.value(), 5000_00L, 100L);
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.RESTING, result.takerStatus);
        assertFalse(result.events.hasEvents());
        assertEquals(2, book.getTotalOrderCount());
    }

    @Test
    @DisplayName("Limit: 部分成交后剩余量挂单")
    void limitShouldRestRemainingAfterPartialFill() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 30L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode buy = makeLimitOrder(2L, 1002L, Side.BUY.value(), 5000_00L, 100L);
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.RESTING, result.takerStatus);
        assertEquals(1, result.events.size());
        assertEquals(30L, result.events.get(0).quantity);
        assertEquals(70L, buy.leavesQty);
        assertEquals(70L, book.bestBid().totalQty);
        assertTrue(book.isAskEmpty());
    }

    // ================================================================
    // Market 撮合
    // ================================================================

    @Test
    @DisplayName("Market: 完全消耗卖盘")
    void marketBuyShouldConsumeAsks() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 100L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode buy = makeOrderOfType(2L, 1002L, Side.BUY.value(), 0L, 100L,
                OrderType.MARKET.value());
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.FULLY_FILLED, result.takerStatus);
        assertEquals(1, result.events.size());
        assertTrue(book.isAskEmpty());
    }

    @Test
    @DisplayName("Market: 深度不足时剩余量撤销")
    void marketShouldCancelRemainingWhenInsufficientDepth() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 30L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode buy = makeOrderOfType(2L, 1002L, Side.BUY.value(), 0L, 100L,
                OrderType.MARKET.value());
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.PARTIAL_FILL_CANCELLED, result.takerStatus);
        assertEquals(30L, buy.filledQty);
        assertEquals(70L, buy.leavesQty);
        assertTrue(book.isAskEmpty());
        assertTrue(book.isBidEmpty());  // Market 单剩余不入簿
    }

    @Test
    @DisplayName("Market: 对手盘为空时直接撤销")
    void marketShouldCancelWhenNoBids() {
        final OrderNode sell = makeOrderOfType(1L, 1001L, Side.SELL.value(), 0L, 100L,
                OrderType.MARKET.value());
        result.reset(sell);
        matcher.match(sell, result);

        assertEquals(MatchResult.TakerStatus.CANCELLED, result.takerStatus);
        assertFalse(result.events.hasEvents());
    }

    // ================================================================
    // IOC 撮合
    // ================================================================

    @Test
    @DisplayName("IOC: 部分成交后剩余量撤销，不挂单")
    void iocShouldCancelRemainingAfterPartialFill() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 40L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode buy = makeOrderOfType(2L, 1002L, Side.BUY.value(), 5000_00L, 100L,
                OrderType.IOC.value());
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.PARTIAL_FILL_CANCELLED, result.takerStatus);
        assertEquals(40L, buy.filledQty);
        assertTrue(book.isBidEmpty());   // IOC 剩余不入簿
    }

    // ================================================================
    // FOK 撮合
    // ================================================================

    @Test
    @DisplayName("FOK: 深度充足时全量成交")
    void fokShouldFillWhenSufficientDepth() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 100L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode buy = makeOrderOfType(2L, 1002L, Side.BUY.value(), 5000_00L, 100L,
                OrderType.FOK.value());
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.FULLY_FILLED, result.takerStatus);
        assertEquals(100L, buy.filledQty);
    }

    @Test
    @DisplayName("FOK: 深度不足时零成交拒绝")
    void fokShouldRejectWhenInsufficientDepth() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 50L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode buy = makeOrderOfType(2L, 1002L, Side.BUY.value(), 5000_00L, 100L,
                OrderType.FOK.value());
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.REJECTED, result.takerStatus);
        assertFalse(result.events.hasEvents());   // 零成交
        assertEquals(1, book.getTotalOrderCount());  // 卖单仍在簿中
    }

    // ================================================================
    // PostOnly 撮合
    // ================================================================

    @Test
    @DisplayName("PostOnly: 无对手盘时挂单成功")
    void postOnlyShouldRestWhenNoOpposite() {
        final OrderNode buy = makeOrderOfType(1L, 1001L, Side.BUY.value(), 5000_00L, 100L,
                OrderType.POST_ONLY.value());
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.RESTING, result.takerStatus);
        assertFalse(result.events.hasEvents());
        assertEquals(5000_00L, book.bestBidPrice());
    }

    @Test
    @DisplayName("PostOnly: 会立即成交时拒绝挂单")
    void postOnlyShouldCancelWhenWouldMatch() {
        // 先挂卖单
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 100L);
        result.reset(sell);
        matcher.match(sell, result);

        // PostOnly 买单价格 >= 最优卖价 → 拒绝
        final OrderNode buy = makeOrderOfType(2L, 1002L, Side.BUY.value(), 5000_00L, 100L,
                OrderType.POST_ONLY.value());
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.CANCELLED, result.takerStatus);
        assertFalse(result.events.hasEvents());
        assertEquals(1, book.getTotalOrderCount());  // 卖单仍在簿
    }

    // ================================================================
    // 撤单
    // ================================================================

    @Test
    @DisplayName("cancel: 撤单后节点从订单簿移除")
    void cancelShouldRemoveOrderFromBook() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 100L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode cancelled = matcher.cancel(1L);
        assertNotNull(cancelled);
        assertEquals(1L, cancelled.orderId);
        assertTrue(book.isAskEmpty());
    }

    @Test
    @DisplayName("cancel: 撤不存在的订单返回 null")
    void cancelShouldReturnNullForUnknownOrder() {
        assertNull(matcher.cancel(999L));
    }
}
