package com.trading.matching.demo;

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import io.aeron.shadow.org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;

/**
 * Disruptor Pipeline Demo。
 * <p>
 * 模拟撮合引擎的 3 段 Pipeline：
 * <p>
 *   [Producer]
 *       ↓
 *   Stage 1: SequenceAssignHandler  （分配序列号）
 *       ↓
 *   Stage 2: MatchingHandler        （模拟撮合）
 *       ↓
 *   Stage 3a: JournalHandler        （模拟持久化，并行）
 *   Stage 3b: ExecReportHandler     （模拟回报，并行）
 * <p>
 * 架构说明：
 *   - Stage 1 和 Stage 2 串行（保证撮合有序）
 *   - Stage 3a 和 Stage 3b 并行（日志和回报可同时进行）
 *   - 整体使用 BusySpin，追求最低延迟
 */
public class DisruptorPipelineDemo {

    private static final Logger log = LoggerFactory.getLogger(DisruptorPipelineDemo.class);

    private static final int  RING_BUFFER_SIZE = 1 << 20;    // 2^20 = 1,048,576
    private static final int  MESSAGE_COUNT    = 1_000_000;
    private static final long WARMUP_COUNT     = 50_000;

    // ========================= 事件定义 =========================

    /**
     * RingBuffer 中的事件对象（预分配，反复复用）。
     *
     * 包含了整个 Pipeline 各阶段需要的所有字段。
     * 每个阶段只写自己负责的字段，读取上游阶段写入的字段。
     */
    static final class OrderEvent {
        // 生产者填充
        long orderId;
        long accountId;
        int  symbolId;
        byte side;          // 1=Buy, 2=Sell
        long price;
        long quantity;
        long sendTimestampNs;  // 发送时间戳（用于测量延迟）

        // Stage 1: SequenceAssignHandler 填充
        long matchSequenceNo;

        // Stage 2: MatchingHandler 填充
        boolean matched;
        long matchPrice;
        long matchQuantity;
        long matchTimestampNs;

        // Stage 3: Handlers 消费，不写入
    }

    /** EventFactory：Disruptor 启动时调用，预分配所有事件对象 */
    static final class OrderEventFactory implements EventFactory<OrderEvent> {
        @Override
        public OrderEvent newInstance() {
            return new OrderEvent();
        }
    }

    // ========================= Stage 1: 序列号分配 =========================
    static final class SequenceAssignHandler implements EventHandler<OrderEvent> {
        private long sequenceCounter = 0L;

        @Override
        public void onEvent(final OrderEvent event,
                            final long sequence,
                            final boolean endOfBatch) {
            // 分配全局单调递增撮合序列号
            event.matchSequenceNo = ++sequenceCounter;
        }
    }

    // ========================= Stage 2: 模拟撮合 =========================
    static final class MatchingHandler implements EventHandler<OrderEvent> {
        // 模拟：买单价格 >= 卖盘最优价时成交
        private long bestAskPrice = 50000_00L;  // 50000.00（精度 0.01，用 long 存储）

        @Override
        public void onEvent(final OrderEvent event,
                            final long sequence,
                            final boolean endOfBatch) {
            // 模拟撮合逻辑（简化）
            if (event.side == 1 && event.price >= bestAskPrice) {
                event.matched = true;
                event.matchPrice = bestAskPrice;
                event.matchQuantity = event.quantity;
                event.matchTimestampNs = System.nanoTime();
                // 模拟 ask 价格变动
                bestAskPrice += 1;
            } else {
                event.matched = false;
            }
        }
    }

    // ========================= Stage 3a: 模拟 Journal =========================
    static final class JournalHandler implements EventHandler<OrderEvent> {
        private long journalCount = 0L;

        @Override
        public void onEvent(final OrderEvent event,
                            final long sequence,
                            final boolean endOfBatch) {
            // 模拟写日志：只记录有成交的事件
            if (event.matched) {
                journalCount++;
                // 实际实现中，这里会调用 Aeron Publication 将事件写入 Journal Service
            }
        }

        long getJournalCount() { return journalCount; }
    }

    // ========================= Stage 3b: 模拟回报 =========================
    static final class ExecReportHandler implements EventHandler<OrderEvent> {
        private final Histogram latencyHistogram;
        private final CountDownLatch doneLatch;
        private final long targetCount;
        private long processedCount = 0L;

        ExecReportHandler(final Histogram histogram,
                          final CountDownLatch latch,
                          final long target) {
            this.latencyHistogram = histogram;
            this.doneLatch = latch;
            this.targetCount = target;
        }

        @Override
        public void onEvent(final OrderEvent event,
                            final long sequence,
                            final boolean endOfBatch) {
            // 测量端到端延迟（从生产者 offer 到此 Handler 处理）
            final long latencyNs = System.nanoTime() - event.sendTimestampNs;
            latencyHistogram.recordValue(Math.min(latencyNs, latencyHistogram.getHighestTrackableValue()));

            processedCount++;
            if (processedCount >= targetCount) {
                doneLatch.countDown();
            }
        }
    }

    // ========================= Main =========================
    public static void main(final String[] args) throws Exception {

        final Histogram latencyHistogram = new Histogram(1, 10_000_000L, 3); // 1ns ~ 10ms
        final CountDownLatch doneLatch = new CountDownLatch(1);

        // 1. 创建 Disruptor
        //    ProducerType.SINGLE：只有一个生产者线程（撮合引擎场景）
        //    BusySpinWaitStrategy：消费者忙轮询，最低延迟
        final ThreadFactory threadFactory = r -> {
            final Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        };

        final Disruptor<OrderEvent> disruptor = new Disruptor<>(
                new OrderEventFactory(),
                RING_BUFFER_SIZE,
                threadFactory,
                ProducerType.SINGLE,    // 单生产者（更快，无需 CAS）
                new BusySpinWaitStrategy()
        );

        // 2. 配置 Pipeline 依赖关系
        final SequenceAssignHandler stage1 = new SequenceAssignHandler();
        final MatchingHandler       stage2 = new MatchingHandler();
        final JournalHandler        stage3a = new JournalHandler();
        final ExecReportHandler     stage3b = new ExecReportHandler(
                latencyHistogram, doneLatch, MESSAGE_COUNT);

        disruptor
                .handleEventsWith(stage1)       // Stage 1 先执行
                .then(stage2)                   // Stage 2 在 Stage 1 之后
                .then(stage3a, stage3b);        // Stage 3a 和 3b 并行（等 Stage 2 完成后）

        // 3. 启动 Disruptor（启动所有消费者线程）
        final RingBuffer<OrderEvent> ringBuffer = disruptor.start();
        log.info("Disruptor started. RingBuffer size: {}", RING_BUFFER_SIZE);

        // 4. 预热
        log.info("Warming up with {} events...", WARMUP_COUNT);
        publishEvents(ringBuffer, (int) WARMUP_COUNT);
        Thread.sleep(200);
        latencyHistogram.reset();
        log.info("Warmup complete.");

        // 5. 正式测量
        log.info("Starting measurement with {} events...", MESSAGE_COUNT);
        final long startNs = System.nanoTime();
        publishEvents(ringBuffer, MESSAGE_COUNT);
        doneLatch.await();
        final long totalNs = System.nanoTime() - startNs;

        // 6. 打印结果
        printResults(MESSAGE_COUNT, totalNs, latencyHistogram, stage3a);

        disruptor.shutdown();
    }

    /**
     * 生产者：批量发布事件到 RingBuffer。
     * <p>
     * 使用 publishEvent + lambda 方式（推荐，避免对象创建）。
     * Lambda 中的 event 是预分配的 OrderEvent 对象，直接填充字段即可。
     */
    private static void publishEvents(final RingBuffer<OrderEvent> ringBuffer,
                                      final int count) {
        for (int i = 0; i < count; i++) {
            // tryPublishEvent：非阻塞，RingBuffer 满时返回 false
            // publishEvent：阻塞等待（适合压测场景）
            ringBuffer.publishEvent((event, sequence) -> {
                event.orderId         = sequence;
                event.accountId       = 1001L;
                event.symbolId        = 1;
                event.side            = (byte) (sequence % 2 == 0 ? 1 : 2);  // 交替买卖
                event.price           = 5000000L + (sequence % 100) * 100;   // 模拟不同价格
                event.quantity        = 100000L;
                event.sendTimestampNs = System.nanoTime();
            });
        }
    }

    private static void printResults(final long msgCount,
                                     final long totalNs,
                                     final Histogram histogram,
                                     final JournalHandler journalHandler) {
        final double totalMs = totalNs / 1e6;
        final double tps     = msgCount / (totalNs / 1e9);

        log.info("=== Disruptor Pipeline Demo Results ===");
        log.info("Messages processed  : {}", msgCount);
        log.info("Journal events      : {}", journalHandler.getJournalCount());
        log.info("Total time          : {} ms", String.format("%.2f", totalMs));
        log.info("Throughput          : {} events/sec", String.format("%.0f", tps));
        log.info("--- End-to-End Latency Distribution ---");
        log.info("Min                 : {} ns", histogram.getMinValue());
        log.info("P50 (median)        : {} ns", histogram.getValueAtPercentile(50));
        log.info("P95                 : {} ns", histogram.getValueAtPercentile(95));
        log.info("P99                 : {} ns", histogram.getValueAtPercentile(99));
        log.info("P99.9               : {} ns", histogram.getValueAtPercentile(99.9));
        log.info("P99.99              : {} ns", histogram.getValueAtPercentile(99.99));
        log.info("Max                 : {} ns", histogram.getMaxValue());
    }
}
