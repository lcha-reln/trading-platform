package com.trading.matching.matcher;

import com.trading.matching.orderbook.*;
import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import com.trading.sbe.TimeInForce;
import com.trading.util.NanoTimeProvider;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OrderMatcher Edge Case 全场景测试。
 *
 * @author Reln Ding
 */
class OrderMatcherEdgeCaseTest {

    private static final int SYMBOL_ID        = 1;
    private static final int MAKER_FEE_MICROS = 1_000;
    private static final int TAKER_FEE_MICROS = 2_000;

    private OrderNodePool pool;
    private OrderBook     book;
    private OrderMatcher  matcher;
    private MatchResult   result;

    @BeforeEach
    void setUp() {
        pool    = new OrderNodePool(4096);
        book    = new OrderBook(SYMBOL_ID, pool);
        matcher = new OrderMatcher(book, MAKER_FEE_MICROS, TAKER_FEE_MICROS,
                NanoTimeProvider.SYSTEM);
        result  = new MatchResult(512);
    }

    // ---- 辅助方法 ----

    private OrderNode limitOrder(final long id, final long acct,
                                 final byte side, final long price, final long qty) {
        final OrderNode n = pool.borrow();
        assertNotNull(n, "Pool exhausted in test setup");
        n.init(id, acct, SYMBOL_ID, side,
                OrderType.LIMIT.value(), TimeInForce.GTC.value(),
                price, qty, 0L, System.nanoTime());
        return n;
    }

    private OrderNode orderOfType(final long id, final byte side,
                                  final long price, final long qty, final byte type) {
        final OrderNode n = pool.borrow();
        assertNotNull(n);
        n.init(id, 1001L, SYMBOL_ID, side, type, TimeInForce.GTC.value(),
                price, qty, 0L, System.nanoTime());
        return n;
    }

    private void restSell(final long id, final long price, final long qty) {
        final OrderNode sell = limitOrder(id, 1001L, Side.SELL.value(), price, qty);
        result.reset(sell);
        matcher.match(sell, result);
        assertEquals(MatchResult.TakerStatus.RESTING, result.takerStatus);
    }

    private void restBuy(final long id, final long price, final long qty) {
        final OrderNode buy = limitOrder(id, 1002L, Side.BUY.value(), price, qty);
        result.reset(buy);
        matcher.match(buy, result);
        assertEquals(MatchResult.TakerStatus.RESTING, result.takerStatus);
    }

    // ================================================================
    // 多档位扫穿
    // ================================================================

    @Nested
    @DisplayName("多档位扫穿")
    class MultiLevelSweep {

        @Test
        @DisplayName("买单扫穿卖盘三个价格档位")
        void buyShouldSweepMultipleAskLevels() {
            // 挂三档卖单
            restSell(1L, 5000_00L, 30L);
            restSell(2L, 5001_00L, 40L);
            restSell(3L, 5002_00L, 50L);

            // 买单价格 5002_00，数量 120，覆盖三档
            final OrderNode buy = limitOrder(4L, 1002L, Side.BUY.value(), 5002_00L, 120L);
            result.reset(buy);
            matcher.match(buy, result);

            assertEquals(MatchResult.TakerStatus.FULLY_FILLED, result.takerStatus);
            assertEquals(3, result.events.size());
            assertEquals(30L,  result.events.get(0).quantity);  // 第一档
            assertEquals(40L,  result.events.get(1).quantity);  // 第二档
            assertEquals(50L,  result.events.get(2).quantity);  // 第三档
            assertEquals(120L, buy.filledQty);
            assertTrue(book.isAskEmpty());
        }

        @Test
        @DisplayName("买单价格不足，只扫穿部分档位后挂单")
        void buyShouldPartialSweepAndRest() {
            restSell(1L, 5000_00L, 50L);
            restSell(2L, 5001_00L, 50L);
            restSell(3L, 5002_00L, 50L);   // 价格超出买单上限

            // 买价 5001_00，只能匹配前两档
            final OrderNode buy = limitOrder(4L, 1002L, Side.BUY.value(), 5001_00L, 200L);
            result.reset(buy);
            matcher.match(buy, result);

            assertEquals(MatchResult.TakerStatus.RESTING, result.takerStatus);
            assertEquals(2, result.events.size());
            assertEquals(100L, buy.filledQty);
            assertEquals(100L, buy.leavesQty);
            assertEquals(1, book.getAsks().size());    // 第三档仍在
            assertEquals(1, book.getBids().size());    // 剩余买单挂入
        }

        @Test
        @DisplayName("市价卖单扫穿买盘直到深度耗尽")
        void marketSellShouldSweepAllBids() {
            restBuy(1L, 5002_00L, 30L);
            restBuy(2L, 5001_00L, 40L);
            restBuy(3L, 5000_00L, 20L);

            final OrderNode sell = orderOfType(4L, Side.SELL.value(), 0L, 100L,
                    OrderType.MARKET.value());
            result.reset(sell);
            matcher.match(sell, result);

            assertEquals(MatchResult.TakerStatus.PARTIAL_FILL_CANCELLED, result.takerStatus);
            assertEquals(3, result.events.size());
            assertEquals(90L, sell.filledQty);
            assertEquals(10L, sell.leavesQty);
            assertTrue(book.isBidEmpty());
            assertTrue(book.isAskEmpty());   // 市价单不挂盘
        }
    }

    // ================================================================
    // 同价位时间优先
    // ================================================================

    @Nested
    @DisplayName("同价位时间优先")
    class TimePriority {

        @Test
        @DisplayName("同价位三张卖单按到达顺序依次成交")
        void shouldFillInTimeOrder() {
            restSell(1L, 5000_00L, 10L);
            restSell(2L, 5000_00L, 20L);
            restSell(3L, 5000_00L, 30L);

            final OrderNode buy = limitOrder(4L, 1002L, Side.BUY.value(), 5000_00L, 60L);
            result.reset(buy);
            matcher.match(buy, result);

            assertEquals(MatchResult.TakerStatus.FULLY_FILLED, result.takerStatus);
            assertEquals(3, result.events.size());
            // 成交顺序：orderId 1 → 2 → 3
            assertEquals(1L, result.events.get(0).makerOrderId);
            assertEquals(2L, result.events.get(1).makerOrderId);
            assertEquals(3L, result.events.get(2).makerOrderId);
        }

        @Test
        @DisplayName("部分成交后同价位剩余挂单位置不变")
        void remainingMakerShouldKeepPosition() {
            restSell(1L, 5000_00L, 100L);
            restSell(2L, 5000_00L, 100L);

            // 买单只成交第一张的部分
            final OrderNode buy1 = limitOrder(3L, 1002L, Side.BUY.value(), 5000_00L, 50L);
            result.reset(buy1);
            matcher.match(buy1, result);

            assertEquals(1, result.events.size());
            assertEquals(1L, result.events.get(0).makerOrderId);
            assertEquals(50L, result.events.get(0).quantity);

            // orderId=1 仍在，leavesQty=50；orderId=2 位置不变
            final PriceLevel level = book.bestAsk();
            assertNotNull(level);
            assertEquals(150L, level.totalQty);   // 50 + 100
            assertEquals(2, level.orderCount);
            assertEquals(1L, level.head.orderId);  // orderId=1 仍是 head
        }
    }

    // ================================================================
    // FOK 边界条件
    // ================================================================

    @Nested
    @DisplayName("FOK 边界条件")
    class FokBoundary {

        @Test
        @DisplayName("FOK: 可用量恰好等于 FOK 数量时成交")
        void fokShouldFillWhenExactlyEnough() {
            restSell(1L, 5000_00L, 50L);
            restSell(2L, 5001_00L, 50L);

            final OrderNode buy = orderOfType(3L, Side.BUY.value(), 5001_00L, 100L,
                    OrderType.FOK.value());
            result.reset(buy);
            matcher.match(buy, result);

            assertEquals(MatchResult.TakerStatus.FULLY_FILLED, result.takerStatus);
            assertEquals(2, result.events.size());
            assertEquals(100L, buy.filledQty);
        }

        @Test
        @DisplayName("FOK: 可用量比需求少 1 单位时拒绝，订单簿不变")
        void fokShouldRejectWhenOneLessUnit() {
            restSell(1L, 5000_00L, 50L);
            restSell(2L, 5001_00L, 49L);   // 总计 99，少 1

            final OrderNode buy = orderOfType(3L, Side.BUY.value(), 5001_00L, 100L,
                    OrderType.FOK.value());
            result.reset(buy);
            matcher.match(buy, result);

            assertEquals(MatchResult.TakerStatus.REJECTED, result.takerStatus);
            assertFalse(result.events.hasEvents());
            // 订单簿状态不变
            assertEquals(2, book.getTotalOrderCount());
            assertEquals(50L, book.getAsks().get(5000_00L).totalQty);
            assertEquals(49L, book.getAsks().get(5001_00L).totalQty);
        }

        @Test
        @DisplayName("FOK: 对手盘价格超出 FOK 价格范围时拒绝")
        void fokShouldRejectWhenPriceOutOfRange() {
            restSell(1L, 5001_00L, 100L);   // 卖价超出买价

            final OrderNode buy = orderOfType(2L, Side.BUY.value(), 5000_00L, 100L,
                    OrderType.FOK.value());
            result.reset(buy);
            matcher.match(buy, result);

            assertEquals(MatchResult.TakerStatus.REJECTED, result.takerStatus);
            assertFalse(result.events.hasEvents());
            assertEquals(1, book.getTotalOrderCount());
        }

        @Test
        @DisplayName("FOK: 对手盘为空时直接拒绝")
        void fokShouldRejectWhenBookEmpty() {
            final OrderNode buy = orderOfType(1L, Side.BUY.value(), 5000_00L, 100L,
                    OrderType.FOK.value());
            result.reset(buy);
            matcher.match(buy, result);

            assertEquals(MatchResult.TakerStatus.REJECTED, result.takerStatus);
        }
    }

    // ================================================================
    // PostOnly 边界条件
    // ================================================================

    @Nested
    @DisplayName("PostOnly 边界条件")
    class PostOnlyBoundary {

        @Test
        @DisplayName("PostOnly 买单价格恰好等于最优卖价时拒绝")
        void postOnlyShouldCancelWhenEqualToBestAsk() {
            restSell(1L, 5000_00L, 100L);

            final OrderNode buy = orderOfType(2L, Side.BUY.value(), 5000_00L, 50L,
                    OrderType.POST_ONLY.value());
            result.reset(buy);
            matcher.match(buy, result);

            assertEquals(MatchResult.TakerStatus.CANCELLED, result.takerStatus);
        }

        @Test
        @DisplayName("PostOnly 买单价格比最优卖价低 1 tick 时挂单成功")
        void postOnlyShouldRestWhenBelowBestAsk() {
            restSell(1L, 5001_00L, 100L);

            final OrderNode buy = orderOfType(2L, Side.BUY.value(), 5000_00L, 50L,
                    OrderType.POST_ONLY.value());
            result.reset(buy);
            matcher.match(buy, result);

            assertEquals(MatchResult.TakerStatus.RESTING, result.takerStatus);
            assertEquals(5000_00L, book.bestBidPrice());
        }

        @Test
        @DisplayName("PostOnly 卖单价格等于最优买价时拒绝")
        void postOnlySellShouldCancelWhenEqualToBestBid() {
            restBuy(1L, 5000_00L, 100L);

            final OrderNode sell = orderOfType(2L, Side.SELL.value(), 5000_00L, 50L,
                    OrderType.POST_ONLY.value());
            result.reset(sell);
            matcher.match(sell, result);

            assertEquals(MatchResult.TakerStatus.CANCELLED, result.takerStatus);
        }
    }

    // ================================================================
    // Limit 单价格边界
    // ================================================================

    @Nested
    @DisplayName("Limit 价格边界")
    class LimitPriceBoundary {

        @Test
        @DisplayName("买单价格比最优卖价高出多个 tick 时，以 Maker 价格成交")
        void buyAboveBestAskShouldFillAtMakerPrice() {
            restSell(1L, 5000_00L, 100L);

            // 买单出价 5010_00，但应以 Maker 价格 5000_00 成交
            final OrderNode buy = limitOrder(2L, 1002L, Side.BUY.value(), 5010_00L, 100L);
            result.reset(buy);
            matcher.match(buy, result);

            assertEquals(MatchResult.TakerStatus.FULLY_FILLED, result.takerStatus);
            assertEquals(5000_00L, result.events.get(0).price);  // Maker 价格优先
        }

        @Test
        @DisplayName("买单价格等于最优卖价时恰好触发成交")
        void buyAtBestAskShouldMatch() {
            restSell(1L, 5000_00L, 100L);

            final OrderNode buy = limitOrder(2L, 1002L, Side.BUY.value(), 5000_00L, 100L);
            result.reset(buy);
            matcher.match(buy, result);

            assertEquals(MatchResult.TakerStatus.FULLY_FILLED, result.takerStatus);
        }

        @Test
        @DisplayName("买单价格比最优卖价低 1 tick 时不成交，挂单")
        void buyOneBelowBestAskShouldRest() {
            restSell(1L, 5000_00L, 100L);

            final OrderNode buy = limitOrder(2L, 1002L, Side.BUY.value(), 4999_99L, 100L);
            result.reset(buy);
            matcher.match(buy, result);

            assertEquals(MatchResult.TakerStatus.RESTING, result.takerStatus);
            assertFalse(result.events.hasEvents());
        }
    }

    // ================================================================
    // 撤单 edge case
    // ================================================================

    @Nested
    @DisplayName("撤单 edge case")
    class CancelEdgeCases {

        @Test
        @DisplayName("撤单后对象池容量恢复")
        void cancelShouldReturnNodeToPool() {
            final int before = pool.available();
            restSell(1L, 5000_00L, 100L);

            final int afterAdd = pool.available();
            assertEquals(before - 1, afterAdd);

            final OrderNode cancelled = matcher.cancel(1L);
            assertNotNull(cancelled);
            pool.release(cancelled);   // 调用方归还

            assertEquals(before, pool.available());
        }

        @Test
        @DisplayName("连续撤同一订单，第二次返回 null")
        void doubleCancel() {
            restSell(1L, 5000_00L, 100L);
            final OrderNode first  = matcher.cancel(1L);
            final OrderNode second = matcher.cancel(1L);
            assertNotNull(first);
            assertNull(second);
        }

        @Test
        @DisplayName("部分成交后再撤单，leavesQty 正确")
        void cancelAfterPartialFill() {
            restSell(1L, 5000_00L, 100L);

            // 部分成交 30
            final OrderNode buy = limitOrder(2L, 1002L, Side.BUY.value(), 5000_00L, 30L);
            result.reset(buy);
            matcher.match(buy, result);

            // 撤剩余 70
            final OrderNode cancelled = matcher.cancel(1L);
            assertNotNull(cancelled);
            assertEquals(70L, cancelled.leavesQty);
            assertEquals(30L, cancelled.filledQty);
            assertTrue(book.isAskEmpty());
        }
    }

    // ================================================================
    // IOC edge case
    // ================================================================

    @Nested
    @DisplayName("IOC edge case")
    class IocEdgeCases {

        @Test
        @DisplayName("IOC 卖单无对手买盘时直接撤销")
        void iocSellWithEmptyBidShouldCancel() {
            final OrderNode sell = orderOfType(1L, Side.SELL.value(), 5000_00L, 100L,
                    OrderType.IOC.value());
            result.reset(sell);
            matcher.match(sell, result);

            assertEquals(MatchResult.TakerStatus.CANCELLED, result.takerStatus);
            assertFalse(result.events.hasEvents());
            assertTrue(book.isAskEmpty());   // IOC 不入簿
        }

        @Test
        @DisplayName("IOC 全量成交时状态为 FULLY_FILLED 而非 PARTIAL_FILL_CANCELLED")
        void iocFullyFilledShouldBeFullyFilled() {
            restSell(1L, 5000_00L, 100L);

            final OrderNode buy = orderOfType(2L, Side.BUY.value(), 5000_00L, 100L,
                    OrderType.IOC.value());
            result.reset(buy);
            matcher.match(buy, result);

            assertEquals(MatchResult.TakerStatus.FULLY_FILLED, result.takerStatus);
        }
    }

    // ================================================================
    // Market edge case
    // ================================================================

    @Nested
    @DisplayName("Market edge case")
    class MarketEdgeCases {

        @Test
        @DisplayName("市价买单精确消耗一个档位后深度耗尽")
        void marketBuyExactlyConsumesSingleLevel() {
            restSell(1L, 5000_00L, 50L);

            final OrderNode buy = orderOfType(2L, Side.BUY.value(), 0L, 50L,
                    OrderType.MARKET.value());
            result.reset(buy);
            matcher.match(buy, result);

            assertEquals(MatchResult.TakerStatus.FULLY_FILLED, result.takerStatus);
            assertEquals(50L, buy.filledQty);
            assertTrue(book.isAskEmpty());
        }

        @Test
        @DisplayName("市价单数量为 1 时与最优价单笔成交")
        void marketBuyOfOneUnit() {
            restSell(1L, 5000_00L, 100L);

            final OrderNode buy = orderOfType(2L, Side.BUY.value(), 0L, 1L,
                    OrderType.MARKET.value());
            result.reset(buy);
            matcher.match(buy, result);

            assertEquals(MatchResult.TakerStatus.FULLY_FILLED, result.takerStatus);
            assertEquals(1, result.events.size());
            assertEquals(1L, result.events.get(0).quantity);
            assertEquals(99L, book.bestAsk().totalQty);
        }
    }

    // ================================================================
    // 异常处理测试
    // ================================================================

    @Nested
    @DisplayName("异常处理")
    class ExceptionHandling {

        @Test
        @DisplayName("未知 orderType 抛出 IllegalArgumentException")
        void unknownOrderTypeShouldThrow() {
            final OrderNode n = pool.borrow();
            assertNotNull(n);
            n.init(1L, 1001L, SYMBOL_ID, Side.BUY.value(),
                    (byte) 99,  // 未知类型
                    TimeInForce.GTC.value(), 5000_00L, 100L, 0L, 0L);
            result.reset(n);

            assertThrows(IllegalArgumentException.class, () -> matcher.match(n, result));
        }
    }
}
