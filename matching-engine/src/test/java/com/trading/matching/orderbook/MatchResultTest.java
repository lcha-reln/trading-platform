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
