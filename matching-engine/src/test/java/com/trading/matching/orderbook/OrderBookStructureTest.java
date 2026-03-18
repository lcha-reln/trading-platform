package com.trading.matching.orderbook;

import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import com.trading.sbe.TimeInForce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OrderBook 数据结构单元测试。
 * <p>
 * 覆盖：挂单、撤单、价格档位管理、对象池、统计字段。
 * 不包含撮合逻辑（见 Part 4 OrderMatcherTest）。
 *
 * @author Reln Ding
 */
public class OrderBookStructureTest {
    private static final int SYMBOL_ID = 1;
    private static final int POOL_SIZE = 1024;

    private OrderNodePool pool;
    private OrderBook book;

    @BeforeEach
    void setUp() {
        pool = new OrderNodePool(POOL_SIZE);
        book = new OrderBook(SYMBOL_ID, pool);
    }

    // ----------------------------------------------------------------
    // 辅助：构造 OrderNode
    // ----------------------------------------------------------------

    private OrderNode makeOrder(final long orderId,
                                final long accountId,
                                final byte side,
                                final long price,
                                final long quantity) {
        final OrderNode node = pool.borrow();
        assertNotNull(node, "Pool is empty");
        node.init(orderId, accountId, SYMBOL_ID,
                side,
                OrderType.LIMIT.value(),
                TimeInForce.GTC.value(),
                price, quantity,
                0L, System.nanoTime());
        return node;
    }

    // ================================================================
    // 挂单测试
    // ================================================================

    @Nested
    @DisplayName("addOrder - 挂单")
    class AddOrderTests {

        @Test
        @DisplayName("买单入簿后可通过 bestBid 查到")
        void shouldAddBuyOrder() {
            final OrderNode node = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            book.addOrder(node);

            assertFalse(book.isBidEmpty());
            assertEquals(5000_00L, book.bestBidPrice());
            assertNotNull(book.bestBid());
            assertEquals(100L, book.bestBid().totalQty);
            assertEquals(1, book.bestBid().orderCount);
            assertEquals(1, book.getTotalOrderCount());
        }

        @Test
        @DisplayName("卖单入簿后可通过 bestAsk 查到")
        void shouldAddSellOrder() {
            final OrderNode node = makeOrder(2L, 1002L, Side.SELL.value(), 5001_00L, 50L);
            book.addOrder(node);

            assertFalse(book.isAskEmpty());
            assertEquals(5001_00L, book.bestAskPrice());
            assertEquals(50L, book.bestAsk().totalQty);
            assertEquals(1, book.getTotalOrderCount());
        }

        @Test
        @DisplayName("同价位多单按时间顺序排列")
        void shouldMaintainTimePriorityAtSamePrice() {
            final OrderNode n1 = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            final OrderNode n2 = makeOrder(2L, 1002L, Side.BUY.value(), 5000_00L, 200L);
            final OrderNode n3 = makeOrder(3L, 1003L, Side.BUY.value(), 5000_00L, 300L);
            book.addOrder(n1);
            book.addOrder(n2);
            book.addOrder(n3);

            final PriceLevel level = book.bestBid();
            assertNotNull(level);
            assertEquals(3, level.orderCount);
            assertEquals(600L, level.totalQty);

            // 链表顺序：n1 → n2 → n3
            assertSame(n1, level.head);
            assertSame(n2, level.head.next);
            assertSame(n3, level.tail);
            assertNull(level.head.prev);
            assertNull(level.tail.next);
        }

        @Test
        @DisplayName("不同价位独立档位，买盘按价格降序")
        void shouldCreateSeparatePriceLevels() {
            book.addOrder(makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L));
            book.addOrder(makeOrder(2L, 1001L, Side.BUY.value(), 4999_00L, 200L));
            book.addOrder(makeOrder(3L, 1001L, Side.BUY.value(), 5001_00L, 300L));

            assertEquals(3, book.getBids().size());
            // 最优买价是 5001_00
            assertEquals(5001_00L, book.bestBidPrice());
            assertEquals(3, book.getTotalOrderCount());
        }

        @Test
        @DisplayName("通过 getOrder 可按 orderId 查到挂单")
        void shouldIndexByOrderId() {
            final OrderNode node = makeOrder(42L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            book.addOrder(node);

            final OrderNode found = book.getOrder(42L);
            assertSame(node, found);
            assertNull(book.getOrder(999L));
        }
    }

    // ================================================================
    // 撤单测试
    // ================================================================

    @Nested
    @DisplayName("removeOrder - 撤单")
    class RemoveOrderTests {

        @Test
        @DisplayName("撤单后订单从索引和档位中移除")
        void shouldRemoveOrderFromBookAndIndex() {
            final OrderNode node = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            book.addOrder(node);

            final OrderNode removed = book.removeOrder(1L);
            assertSame(node, removed);
            assertNull(book.getOrder(1L));
            assertTrue(book.isBidEmpty());
            assertEquals(0, book.getTotalOrderCount());
        }

        @Test
        @DisplayName("撤中间节点，链表结构保持正确")
        void shouldRemoveMiddleNodeCorrectly() {
            final OrderNode n1 = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            final OrderNode n2 = makeOrder(2L, 1002L, Side.BUY.value(), 5000_00L, 200L);
            final OrderNode n3 = makeOrder(3L, 1003L, Side.BUY.value(), 5000_00L, 300L);
            book.addOrder(n1);
            book.addOrder(n2);
            book.addOrder(n3);

            book.removeOrder(2L);  // 撤中间节点 n2

            final PriceLevel level = book.bestBid();
            assertEquals(2, level.orderCount);
            assertEquals(400L, level.totalQty);
            assertSame(n1, level.head);
            assertSame(n3, level.tail);
            assertSame(n3, n1.next);
            assertSame(n1, n3.prev);
            assertNull(n2.prev);
            assertNull(n2.next);
        }

        @Test
        @DisplayName("撤最后一单后，该价格档位被清除")
        void shouldRemovePriceLevelWhenEmpty() {
            book.addOrder(makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L));
            book.removeOrder(1L);

            assertTrue(book.isBidEmpty());
            assertEquals(0, book.getBids().size());
        }

        @Test
        @DisplayName("撤不存在的订单返回 null")
        void shouldReturnNullForNonExistentOrder() {
            assertNull(book.removeOrder(999L));
        }
    }

    // ================================================================
    // onFill 测试（成交更新）
    // ================================================================

    @Nested
    @DisplayName("onFill - 成交更新")
    class OnFillTests {

        @Test
        @DisplayName("部分成交只更新 leavesQty 和 totalQty，不移除节点")
        void shouldUpdateQtyOnPartialFill() {
            final OrderNode node = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            book.addOrder(node);

            book.onFill(node, 30L, false);

            assertEquals(30L, node.filledQty);
            assertEquals(70L, node.leavesQty);
            assertEquals(70L, book.bestBid().totalQty);
            assertEquals(1, book.getTotalOrderCount());   // 仍在簿中
            assertNotNull(book.getOrder(1L));
        }

        @Test
        @DisplayName("全部成交后节点从订单簿中移除")
        void shouldRemoveNodeOnFullFill() {
            final OrderNode node = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            book.addOrder(node);

            book.onFill(node, 100L, true);

            assertNull(book.getOrder(1L));
            assertTrue(book.isBidEmpty());
            assertEquals(0, book.getTotalOrderCount());
        }
    }

    // ================================================================
    // 对象池测试
    // ================================================================

    @Nested
    @DisplayName("OrderNodePool - 对象池")
    class PoolTests {

        @Test
        @DisplayName("借出再归还后可再次借出（复用）")
        void shouldReuseNodeAfterRelease() {
            final OrderNode n = pool.borrow();
            assertNotNull(n);
            final int before = pool.available();
            n.reset();
            pool.release(n);
            assertEquals(before + 1, pool.available());
            assertSame(n, pool.borrow());
        }

        @Test
        @DisplayName("池空时 borrow 返回 null")
        void shouldReturnNullWhenEmpty() {
            final OrderNodePool tiny = new OrderNodePool(1);
            assertNotNull(tiny.borrow());
            assertNull(tiny.borrow());   // 第二次借出，池空
        }

        @Test
        @DisplayName("容量为 0 时构造抛 IllegalArgumentException")
        void shouldThrowOnZeroCapacity() {
            assertThrows(IllegalArgumentException.class, () -> new OrderNodePool(0));
        }

        @Test
        @DisplayName("负容量时构造抛 IllegalArgumentException")
        void shouldThrowOnNegativeCapacity() {
            assertThrows(IllegalArgumentException.class, () -> new OrderNodePool(-1));
        }
    }

    // ================================================================
    // PriceLevel 测试
    // ================================================================

    @Nested
    @DisplayName("PriceLevel - 价格档位")
    class PriceLevelTests {

        @Test
        @DisplayName("新建档位应为空")
        void shouldBeEmptyOnCreation() {
            final PriceLevel level = new PriceLevel(5000_00L);
            assertTrue(level.isEmpty());
            assertEquals(0L, level.totalQty);
            assertEquals(0, level.orderCount);
            assertNull(level.head);
            assertNull(level.tail);
        }

        @Test
        @DisplayName("addOrder 后 head 和 tail 均指向该节点")
        void shouldSetHeadAndTailOnFirstAdd() {
            final PriceLevel level = new PriceLevel(5000_00L);
            final OrderNode n = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            level.addOrder(n);

            assertSame(n, level.head);
            assertSame(n, level.tail);
            assertNull(n.prev);
            assertNull(n.next);
        }

        @Test
        @DisplayName("removeOrder 后 isEmpty 返回 true")
        void shouldBeEmptyAfterRemovingLastOrder() {
            final PriceLevel level = new PriceLevel(5000_00L);
            final OrderNode n = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            level.addOrder(n);
            level.removeOrder(n);

            assertTrue(level.isEmpty());
            assertNull(level.head);
            assertNull(level.tail);
        }
    }

    // ================================================================
    // 统计字段测试
    // ================================================================

    @Nested
    @DisplayName("统计字段")
    class StatisticsTests {

        @Test
        @DisplayName("recordTrade 正确累加 volume24h 和 turnover24h")
        void shouldAccumulateTradeStatistics() {
            book.recordTrade(5000_00L, 100L, 500_000L);
            book.recordTrade(5001_00L, 50L, 250_050L);

            assertEquals(5001_00L, book.getLastTradePrice());
            assertEquals(50L, book.getLastTradeQty());
            assertEquals(150L, book.getVolume24h());
            assertEquals(750_050L, book.getTurnover24h());
        }
    }
}
