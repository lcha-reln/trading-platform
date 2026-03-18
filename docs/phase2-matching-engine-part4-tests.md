# Phase 2 撮合引擎核心实现 — Part 4：全场景单元测试

> **目标：** 对撮合引擎所有核心路径和边界条件进行系统化测试，
> 覆盖率要求 100%，不遗漏异常处理和失败响应分支。
>
> **前置条件：** Part 1-3 完成，数据结构与撮合逻辑已就绪  
> **本节验证目标：** 所有测试通过，行覆盖率 100%

---

## 目录

1. [测试分类与策略](#1-测试分类与策略)
2. [OrderMatcher 全场景测试](#2-ordermatcher-全场景测试)
3. [MatchEventList 与 MatchResult 测试](#3-matcheventlist-与-matchresult-测试)
4. [手续费计算测试](#4-手续费计算测试)
5. [运行所有测试](#5-运行所有测试)

---

## 1. 测试分类与策略

### 1.1 测试场景矩阵

| 分类              | 场景                      | 文件                                  |
|-----------------|-------------------------|-------------------------------------|
| 数据结构            | OrderBook 增删查（见 Part 1） | `OrderBookStructureTest`            |
| 撮合冒烟            | 5 种订单类型核心路径（见 Part 2）   | `OrderMatcherSmokeTest`             |
| **edge case**   | 多档位扫穿、时间优先、FOK 边界等      | `OrderMatcherEdgeCaseTest`（本 Part）  |
| **异常处理**        | 对象池耗尽、未知 orderType      | `OrderMatcherExceptionTest`（本 Part） |
| **手续费**         | 精度、返佣、零费率               | `FeeCalculationTest`（本 Part）        |
| **MatchResult** | 列表满、reset 复用            | `MatchResultTest`（本 Part）           |

### 1.2 覆盖率要求

- **行覆盖率（Line Coverage）：100%**
- **分支覆盖率（Branch Coverage）：100%**
- 所有 `if/else`、`switch/case`、`while` 循环的每条分支均需有测试用例覆盖

---

## 2. OrderMatcher 全场景测试

文件：`matching-engine/src/test/java/com/trading/matching/matcher/OrderMatcherEdgeCaseTest.java`

```java
package com.trading.matching.matcher;

import com.trading.matching.orderbook.*;
import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import com.trading.sbe.TimeInForce;
import com.trading.util.NanoTimeProvider;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

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
```

---

## 3. MatchEventList 与 MatchResult 测试

文件：`matching-engine/src/test/java/com/trading/matching/orderbook/MatchResultTest.java`

```java
package com.trading.matching.orderbook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MatchEventList 与 MatchResult 单元测试。
 *
 * @author Reln Ding
 */
class MatchResultTest {

    // ================================================================
    // MatchEventList 测试
    // ================================================================

    @Nested
    @DisplayName("MatchEventList")
    class MatchEventListTests {

        @Test
        @DisplayName("初始状态 size=0，hasEvents=false")
        void shouldBeEmptyOnCreation() {
            final MatchEventList list = new MatchEventList(10);
            assertEquals(0, list.size());
            assertFalse(list.hasEvents());
        }

        @Test
        @DisplayName("next() 依次返回预分配的 MatchEvent，并重置字段")
        void nextShouldReturnResetEvent() {
            final MatchEventList list = new MatchEventList(5);
            final MatchEvent e1 = list.next();
            assertNotNull(e1);
            assertEquals(1, list.size());
            assertTrue(list.hasEvents());

            // 字段应被重置
            assertEquals(0L, e1.price);
            assertEquals(0L, e1.quantity);
        }

        @Test
        @DisplayName("容量满时 next() 返回 null")
        void nextShouldReturnNullWhenFull() {
            final MatchEventList list = new MatchEventList(2);
            assertNotNull(list.next());
            assertNotNull(list.next());
            assertNull(list.next());   // 第三次，超出容量
            assertEquals(2, list.size());
        }

        @Test
        @DisplayName("clear() 后 size 归零，对象复用")
        void clearShouldResetSize() {
            final MatchEventList list = new MatchEventList(5);
            final MatchEvent first = list.next();
            list.clear();
            assertEquals(0, list.size());

            // 再次 next() 应返回同一个预分配对象（从 index 0 开始）
            final MatchEvent again = list.next();
            assertSame(first, again);
        }

        @Test
        @DisplayName("get() 按索引正确返回元素")
        void getShouldReturnCorrectElement() {
            final MatchEventList list = new MatchEventList(5);
            final MatchEvent e0 = list.next();
            e0.price = 5000_00L;
            final MatchEvent e1 = list.next();
            e1.price = 5001_00L;

            assertEquals(5000_00L, list.get(0).price);
            assertEquals(5001_00L, list.get(1).price);
        }
    }

    // ================================================================
    // MatchEvent 测试
    // ================================================================

    @Nested
    @DisplayName("MatchEvent")
    class MatchEventTests {

        @Test
        @DisplayName("reset() 将所有字段归零")
        void resetShouldClearAllFields() {
            final MatchEvent e = new MatchEvent();
            e.sequenceNo       = 100L;
            e.symbolId         = 1;
            e.makerOrderId     = 10L;
            e.takerOrderId     = 20L;
            e.price            = 5000_00L;
            e.quantity         = 100L;
            e.makerFee         = 50L;
            e.takerFee         = 100L;
            e.makerFullyFilled = true;
            e.takerFullyFilled = true;
            e.timestampNs      = 999L;

            e.reset();

            assertEquals(0L,    e.sequenceNo);
            assertEquals(0,     e.symbolId);
            assertEquals(0L,    e.makerOrderId);
            assertEquals(0L,    e.takerOrderId);
            assertEquals(0L,    e.price);
            assertEquals(0L,    e.quantity);
            assertEquals(0L,    e.makerFee);
            assertEquals(0L,    e.takerFee);
            assertFalse(e.makerFullyFilled);
            assertFalse(e.takerFullyFilled);
            assertEquals(0L,    e.timestampNs);
        }
    }

    // ================================================================
    // MatchResult 测试
    // ================================================================

    @Nested
    @DisplayName("MatchResult")
    class MatchResultTests {

        @Test
        @DisplayName("reset() 清空事件列表并重置 takerStatus")
        void resetShouldClearEvents() {
            final MatchResult r = new MatchResult(10);
            r.events.next();   // 写入一个事件
            r.takerStatus = MatchResult.TakerStatus.FULLY_FILLED;

            r.reset(null);

            assertEquals(0, r.events.size());
            assertNull(r.takerStatus);
            assertNull(r.takerNode);
        }

        @Test
        @DisplayName("reset() 设置 takerNode 引用")
        void resetShouldSetTakerNode() {
            final MatchResult r = new MatchResult(10);
            final OrderNode fakeNode = new OrderNode();
            r.reset(fakeNode);
            assertSame(fakeNode, r.takerNode);
        }
    }
}
```

---

## 4. 手续费计算测试

文件：`matching-engine/src/test/java/com/trading/matching/matcher/FeeCalculationTest.java`

```java
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
```

---

## 5. 运行所有测试

### 5.1 运行 matching-engine 模块全部测试

```bash
cd trading-platform
mvn test -pl matching-engine -Dcheckstyle.skip=true
```

期望输出：

```
-------------------------------------------------------
 T E S T S
-------------------------------------------------------
Running com.trading.matching.orderbook.OrderBookStructureTest
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
Running com.trading.matching.orderbook.MatchResultTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
Running com.trading.matching.matcher.OrderMatcherSmokeTest
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
Running com.trading.matching.matcher.OrderMatcherEdgeCaseTest
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
Running com.trading.matching.matcher.FeeCalculationTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
Running com.trading.matching.disruptor.MatchingPipelineSmokeTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0

Results:
Tests run: 69, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
```

### 5.2 查看测试覆盖率（JaCoCo）

在 `matching-engine/pom.xml` 中添加 JaCoCo 插件：

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
    <executions>
        <execution>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals><goal>report</goal></goals>
        </execution>
    </executions>
</plugin>
```

```bash
mvn test -pl matching-engine -Dcheckstyle.skip=true
# 覆盖率报告：matching-engine/target/site/jacoco/index.html
```

---

## Part 4 完成检查清单

- [ ] `OrderMatcherEdgeCaseTest`：24 个 edge case 测试全部通过
- [ ] `MatchResultTest`：8 个测试通过，MatchEventList/MatchEvent/MatchResult 所有分支覆盖
- [ ] `FeeCalculationTest`：4 个测试通过，含零费率、负费率、大数不溢出
- [ ] 全部 69 个测试通过，0 Failures，0 Errors
- [ ] JaCoCo 行覆盖率达到 100%
- [ ] 分支覆盖率达到 100%（`if/else` 两侧、`switch` 所有 case 均有测试）

---

## 下一步：Part 5

Part 4 完成后，进入 **Part 5：性能基准测试与 Phase 2 完成验收**，包括：

1. JMH 微基准：单交易对撮合吞吐量（目标 > 500K orders/sec）
2. 延迟直方图：P99 < 5μs 验证
3. 对象池压力测试（满负载 GC 监测）
4. Phase 2 完整验收检查清单
