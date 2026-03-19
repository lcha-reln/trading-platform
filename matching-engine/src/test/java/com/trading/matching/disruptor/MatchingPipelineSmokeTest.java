package com.trading.matching.disruptor;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.trading.matching.matcher.OrderMatcher;
import com.trading.matching.orderbook.OrderBook;
import com.trading.matching.orderbook.OrderNode;
import com.trading.matching.orderbook.OrderNodePool;
import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import com.trading.sbe.TimeInForce;
import com.trading.util.NanoTimeProvider;
import io.aeron.Publication;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MatchingDisruptor Pipeline 集成冒烟测试（Mock Aeron Publication）。
 *
 * @author Reln Ding
 */
public class MatchingPipelineSmokeTest {
    private static final int SYMBOL_ID = 1;

    private OrderNodePool pool;
    private OrderBook book;
    private OrderMatcher matcher;

    // Mock Aeron Publications
    private Publication journalPub;
    private Publication execPub;
    private Publication mktPub;

    // 被测 Disruptor 组件（不含 Aeron 订阅，直接向 RingBuffer 发布）
    private Disruptor<MatchingEvent> disruptor;
    private RingBuffer<MatchingEvent> ringBuffer;

    @BeforeEach
    void setUp() {
        pool = new OrderNodePool(1024);
        book = new OrderBook(SYMBOL_ID, pool);
        matcher = new OrderMatcher(book, 1_000, 2_000, NanoTimeProvider.SYSTEM);

        journalPub = mock(Publication.class);
        execPub = mock(Publication.class);
        mktPub = mock(Publication.class);

        when(journalPub.offer(any(DirectBuffer.class), anyInt(), anyInt()))
                .thenReturn(1L);
        when(execPub.offer(any(DirectBuffer.class), anyInt(), anyInt()))
                .thenReturn(1L);
        when(mktPub.offer(any(DirectBuffer.class), anyInt(), anyInt()))
                .thenReturn(1L);

        // 直接组装 Disruptor（不含 InboundSubscriber）
        final SequenceAssignHandler stage1 = new SequenceAssignHandler();
        final MatchingHandler stage2 = new MatchingHandler(matcher);
        final JournalPublishHandler stage3a = new JournalPublishHandler(journalPub);
        final ExecutionReportHandler stage3b = new ExecutionReportHandler(execPub);
        final MarketDataPublishHandler stage3c = new MarketDataPublishHandler(mktPub);

        disruptor = new com.lmax.disruptor.dsl.Disruptor<>(
                MatchingEvent.FACTORY, 1024,
                r -> new Thread(r, "test-disruptor"),
                com.lmax.disruptor.dsl.ProducerType.SINGLE,
                new com.lmax.disruptor.BusySpinWaitStrategy()
        );
        disruptor.handleEventsWith(stage1).then(stage2).then(stage3a, stage3b, stage3c);
        ringBuffer = disruptor.start();
    }

    @AfterEach
    void tearDown() {
        disruptor.shutdown();
    }

    private OrderNode makeNode(final long orderId, final byte side,
                               final long price, final long qty) {
        final OrderNode n = pool.borrow();
        assertNotNull(n);
        n.init(orderId, 1001L, SYMBOL_ID, side,
                OrderType.LIMIT.value(), TimeInForce.GTC.value(),
                price, qty, 0L, System.nanoTime());
        return n;
    }

    @Test
    @DisplayName("Pipeline: 卖单挂单 + 买单触发撮合，Stage3 回调触发")
    void pipelineShouldTriggerStage3OnMatch() throws InterruptedException {
        final AtomicInteger mktCallCount = new AtomicInteger(0);
        when(mktPub.offer(any(DirectBuffer.class), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    mktCallCount.incrementAndGet();
                    return 1L;
                });

        // Step 1: 挂卖单（不会成交，Stage3 无行情推送）
        final OrderNode sell = makeNode(1L, Side.SELL.value(), 5000_00L, 100L);
        ringBuffer.publishEvent((e, seq) -> {
            e.reset();
            e.eventType = 1;
            e.orderNode = sell;
        });

        Thread.sleep(50);   // 等 Pipeline 处理
        assertEquals(0, mktCallCount.get());

        // Step 2: 买单触发撮合，应产生行情推送
        final OrderNode buy = makeNode(2L, Side.BUY.value(), 5000_00L, 100L);
        ringBuffer.publishEvent((e, seq) -> {
            e.reset();
            e.eventType = 1;
            e.orderNode = buy;
        });

        Thread.sleep(100);
        assertTrue(mktCallCount.get() >= 1, "MarketData should have been published");

        // Journal 和 ExecReport 也应被调用
        verify(journalPub, atLeastOnce()).offer(any(DirectBuffer.class), anyInt(), anyInt());
        verify(execPub, atLeastOnce()).offer(any(DirectBuffer.class), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Pipeline: 撤单事件正确路由到 MatchingHandler")
    void pipelineShouldRouteCancelEventCorrectly() throws InterruptedException {
        // 先挂一张卖单
        final OrderNode sell = makeNode(1L, Side.SELL.value(), 5000_00L, 100L);
        ringBuffer.publishEvent((e, seq) -> {
            e.reset();
            e.eventType = 1;
            e.orderNode = sell;
        });
        Thread.sleep(50);

        // 发撤单事件
        final CountDownLatch latch = new CountDownLatch(1);
        when(execPub.offer(any(DirectBuffer.class), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    latch.countDown();
                    return 1L;
                });

        ringBuffer.publishEvent((e, seq) -> {
            e.reset();
            e.eventType = 2;
            e.cancelOrderId = 1L;
        });

        // 验证订单簿已清空（撤单后）
        Thread.sleep(100);
        assertTrue(book.isAskEmpty(), "Ask side should be empty after cancel");
    }
}
