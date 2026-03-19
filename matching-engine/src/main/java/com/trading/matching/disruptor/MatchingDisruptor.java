package com.trading.matching.disruptor;

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.trading.matching.aeron.InboundOrderSubscriber;
import com.trading.matching.matcher.OrderMatcher;
import com.trading.matching.orderbook.OrderBook;
import io.aeron.Publication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;

/**
 * 撮合引擎 Disruptor Pipeline 装配器。
 *
 * <p>Pipeline 拓扑：
 * <pre>
 * [InboundSubscriber] → RingBuffer
 *   → Stage1: SequenceAssignHandler
 *   → Stage2: MatchingHandler
 *   → Stage3: [JournalPublishHandler || ExecutionReportHandler || MarketDataPublishHandler]
 * </pre>
 *
 * @author Reln Ding
 */
public class MatchingDisruptor {
    private static final Logger log = LoggerFactory.getLogger(MatchingDisruptor.class);

    /**
     * RingBuffer 大小（必须为 2 的幂）
     */
    private static final int RING_BUFFER_SIZE = 1 << 20;   // 1,048,576

    private final Disruptor<MatchingEvent> disruptor;
    private final RingBuffer<MatchingEvent> ringBuffer;
    private final InboundOrderSubscriber inboundSubscriber;
    private final Thread inboundThread;

    public MatchingDisruptor(final OrderBook orderBook,
                             final OrderMatcher matcher,
                             final io.aeron.Subscription inboundSubscription,
                             final Publication journalPublication,
                             final Publication execReportPublication,
                             final Publication marketDataPublication) {
        // 1.创建 handler 实例
        final SequenceAssignHandler stage1 = new SequenceAssignHandler();
        final MatchingHandler stage2 = new MatchingHandler(matcher);
        final JournalPublishHandler stage3a = new JournalPublishHandler(journalPublication);
        final ExecutionReportHandler stage3b = new ExecutionReportHandler(execReportPublication);
        final MarketDataPublishHandler stage3c = new MarketDataPublishHandler(marketDataPublication);

        // 2.创建 disruptor
        final ThreadFactory threadFactory = r -> {
            final Thread t = new Thread(r, "matching-disruptor-" + orderBook.symbolId);
            t.setDaemon(false);
            return t;
        };

        this.disruptor = new Disruptor<>(
                MatchingEvent.FACTORY,
                RING_BUFFER_SIZE,
                threadFactory,
                ProducerType.SINGLE,
                new BusySpinWaitStrategy()
        );

        // 3.分配 Pipeline 拓扑
        disruptor.handleEventsWith(stage1).then(stage2).then(stage3a, stage3b, stage3c);

        // 4.启动 Disruptor
        this.ringBuffer = disruptor.start();
        log.info("MatchingDisruptor started for symbolId={}, ringBufferSize={}",
                orderBook.symbolId, RING_BUFFER_SIZE);

        // 5.创建 InboundSubscriber（从 Aeron 读入并写入 RingBuffer）
        this.inboundSubscriber = new InboundOrderSubscriber(inboundSubscription, ringBuffer, orderBook);
        this.inboundThread = new Thread(inboundSubscriber, "inbound-subscriber-" + orderBook.symbolId);
        this.inboundThread.setDaemon(false);
        this.inboundThread.start();

        log.info("InboundOrderSubscriber started for symbolId={}", orderBook.symbolId);
    }

    /**
     * 优雅关闭
     */
    public void shutdown() {
        inboundSubscriber.stop();
        try {
            inboundThread.join(5000);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        disruptor.shutdown();
        log.info("MatchingDisruptor shutdown complete.");
    }

    /**
     * 获取 RingBuffer（供测试使用）
     */
    public RingBuffer<MatchingEvent> getRingBuffer() {
        return ringBuffer;
    }
}
