package com.trading.matching.demo;

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.shadow.org.HdrHistogram.Histogram;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 完整链路 Demo：Aeron IPC → Disruptor Pipeline → Aeron IPC
 * <p>
 * 此 Demo 验证：
 * 1. 外部消息通过 Aeron IPC 进入系统
 * 2. Disruptor Pipeline 处理消息（3段）
 * 3. 处理结果通过 Aeron IPC 输出
 * 4. 端到端延迟测量
 * <p>
 * 这是 Phase 1 的最终验证目标：
 * 单链路端到端延迟 P99 < 1μs（Linux 物理机）
 * <p>
 * 执行：mvn exec:exec@run -pl matching-engine -Dexec.mainClass=com.trading.matching.demo.FullPipelineDemo
 */
public class FullPipelineDemo {

    static final int FIELD_SEQ_NO = 0;
    static final int FIELD_SEND_TS = 8;
    static final int FIELD_PROC_SEQ_NO = 16;
    static final int FIELD_PROC_TS = 24;
    static final int MSG_LENGTH = 32;
    private static final Logger log = LoggerFactory.getLogger(FullPipelineDemo.class);
    private static final String INBOUND_CHANNEL = "aeron:ipc";
    private static final int INBOUND_STREAM = 10;
    private static final String OUTBOUND_CHANNEL = "aeron:ipc";

    // ---- 消息格式（直接在 DirectBuffer 上操作）----
    // Offset 0  : int64 sequenceNo（生产者填写）
    // Offset 8  : int64 sendTimestampNs（生产者填写）
    // Offset 16 : int64 processedSequenceNo（Disruptor Stage 1 填写）
    // Offset 24 : int64 processedTimestampNs（Disruptor Stage 2 填写）
    // Total: 32 bytes
    private static final int OUTBOUND_STREAM = 11;
    private static final int RING_BUFFER_SIZE = 1 << 20;
    private static final int WARMUP_COUNT = 50_000;
    private static final int MESSAGE_COUNT = 500_000;
    // waitForConnection 最长等待时间
    private static final long CONNECTION_TIMEOUT_MS = 5_000;

    public static void main(String[] args) throws InterruptedException {
        final String aeronDir = System.getProperty("aeron.dir", "/tmp/aeron-full-demo");
        final Histogram latencyHistogram = new Histogram(1, 100_000_000L, 3);
        // ping-pong 同步：每发一条消息，等 outbound 回来后置 true，主线程才发下一条
        final AtomicBoolean pongReceived = new AtomicBoolean(false);
        final AtomicLong receivedCount = new AtomicLong(0);

        // 1. 启动 MediaDriver
        log.info("Launching MediaDriver, aeronDir={}", aeronDir);
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .threadingMode(ThreadingMode.DEDICATED)
                .conductorIdleStrategy(new BusySpinIdleStrategy())
                .senderIdleStrategy(new BusySpinIdleStrategy())
                .receiverIdleStrategy(new BusySpinIdleStrategy())
                .aeronDirectoryName(aeronDir);

        // fix: Aeron 纳入 try-with-resources，确保异常时也能正确关闭
        try (MediaDriver driver = MediaDriver.launch(driverCtx);
             Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir))) {

            log.info("MediaDriver and Aeron connected");

            // 2. 创建 Aeron 通道
            // fix: outboundPub/outboundSub 提升到外层 try-with-resources，
            //      避免在 try 块结束后被 Disruptor Stage 3 的 lambda 引用已关闭资源
            try (Publication inboundPub = aeron.addPublication(INBOUND_CHANNEL, INBOUND_STREAM);
                 Subscription inboundSub = aeron.addSubscription(INBOUND_CHANNEL, INBOUND_STREAM);
                 Publication outboundPub = aeron.addPublication(OUTBOUND_CHANNEL, OUTBOUND_STREAM);
                 Subscription outboundSub = aeron.addSubscription(OUTBOUND_CHANNEL, OUTBOUND_STREAM)) {

                log.info("Waiting for inbound connection (channel={}, stream={})...",
                        INBOUND_CHANNEL, INBOUND_STREAM);
                waitForConnection(inboundPub, inboundSub, "inbound");

                log.info("Waiting for outbound connection (channel={}, stream={})...",
                        OUTBOUND_CHANNEL, OUTBOUND_STREAM);
                waitForConnection(outboundPub, outboundSub, "outbound");

                log.info("All Aeron channels connected");

                // 3. 创建 Disruptor
                log.info("Starting Disruptor, ringBufferSize={}", RING_BUFFER_SIZE);
                final Disruptor<PipelineEvent> disruptor = new Disruptor<>(
                        new PipelineEventFactory(),
                        RING_BUFFER_SIZE,
                        (Runnable r) -> new Thread(r, "disruptor-worker"),
                        ProducerType.SINGLE,
                        new BusySpinWaitStrategy()
                );

                // Stage 1：读取 Aeron 消息并填充 RingBuffer（由 AeronInboundSubscriber 直接 publish）
                // Stage 2：处理逻辑（分配序列号）
                // Stage 3：写出到 Aeron IPC outbound
                disruptor
                        .handleEventsWith(
                                // Stage 1：标记序列号
                                (event, seq, eob) -> event.data.putLong(FIELD_PROC_SEQ_NO, seq)
                        )
                        .then(
                                // Stage 2：模拟处理（记录处理时间戳）
                                (event, seq, eob) -> event.data.putLong(FIELD_PROC_TS, System.nanoTime())
                        )
                        .then(
                                // Stage 3：写出到 Aeron outbound
                                (event, seq, eob) -> {
                                    long result;
                                    while ((result = outboundPub.offer(event.data, 0, MSG_LENGTH)) < 0) {
                                        // fix: CLOSED 时记录错误并终止，而不是静默 break
                                        if (result == Publication.CLOSED) {
                                            log.error("outboundPub is CLOSED at seq={}, dropping message", seq);
                                            break;
                                        }
                                        if (result == Publication.MAX_POSITION_EXCEEDED) {
                                            log.error("outboundPub MAX_POSITION_EXCEEDED at seq={}", seq);
                                            break;
                                        }
                                        Thread.onSpinWait();
                                    }
                                }
                        );

                // fix: disruptor.start() 返回 RingBuffer，不应通过 .asRingBuffer() 获取
                final RingBuffer<PipelineEvent> ringBuffer = disruptor.start();
                log.info("Disruptor started");

                // 4. 启动 Inbound Subscriber（读取 Aeron 消息后 publish 到 Disruptor）
                final long totalCount = WARMUP_COUNT + MESSAGE_COUNT;
                final AtomicLong inCount = new AtomicLong(0);
                final Thread inboundThread = new Thread(() -> {
                    final FragmentHandler inboundHandler = (buffer, offset, length, header) -> {
                        ringBuffer.publishEvent((event, seq) ->
                                event.data.putBytes(0, buffer, offset, length));
                        inCount.incrementAndGet();
                    };
                    final IdleStrategy idle = new BusySpinIdleStrategy();
                    while (inCount.get() < totalCount) {
                        idle.idle(inboundSub.poll(inboundHandler, 10));
                    }
                    log.info("inbound-subscriber finished, forwarded={}", inCount.get());
                }, "inbound-subscriber");
                inboundThread.setDaemon(true);
                inboundThread.start();

                // 5. 启动 Outbound Subscriber（ping-pong 模式：收到回包后唤醒主线程发下一条）
                //    延迟 = System.nanoTime()（收到时）- sendTs（发送时写入消息体）
                //    ping-pong 保证每次只有一条消息在途，队列中永远没有积压，
                //    测出的是单条消息穿越完整链路的真实延迟，而非排队等待时间。
                final Thread outboundThread = new Thread(() -> {
                    final FragmentHandler outboundHandler = (buffer, offset, length, header) -> {
                        final long receiveTs = System.nanoTime();
                        final long sendTs = buffer.getLong(offset + FIELD_SEND_TS);
                        final long latency = receiveTs - sendTs;
                        final long count = receivedCount.incrementAndGet();
                        // 跳过热身阶段，只统计正式测量数据
                        if (count > WARMUP_COUNT) {
                            latencyHistogram.recordValue(Math.min(latency, latencyHistogram.getHighestTrackableValue()));
                        }
                        // 通知主线程可以发下一条（ping-pong 信号）
                        pongReceived.setRelease(true);
                    };
                    final IdleStrategy idle = new BusySpinIdleStrategy();
                    while (receivedCount.get() < totalCount) {
                        idle.idle(outboundSub.poll(outboundHandler, 10));
                    }
                    log.info("outbound-subscriber finished, received={}", receivedCount.get());
                }, "outbound-subscriber");
                outboundThread.setDaemon(true);
                outboundThread.start();

                // 6. 主线程 ping-pong 发送
                Thread.sleep(100); // 等各线程就绪

                final UnsafeBuffer sendBuffer = new UnsafeBuffer(new byte[MSG_LENGTH]);

                // 热身阶段：触发 JIT 编译、CPU 分支预测、OS 调度稳定
                log.info("Warming up, messages={}", WARMUP_COUNT);
                pingPongSend(inboundPub, sendBuffer, pongReceived, 0, WARMUP_COUNT);
                // 等热身消息全部被 outbound 消费完，确保正式测量不受影响
                while (receivedCount.get() < WARMUP_COUNT) {
                    Thread.onSpinWait();
                }
                latencyHistogram.reset();
                log.info("Warmup done, starting measurement, messages={}", MESSAGE_COUNT);

                // 正式测量
                final long startNs = System.nanoTime();
                pingPongSend(inboundPub, sendBuffer, pongReceived, WARMUP_COUNT, MESSAGE_COUNT);
                // ping-pong 模式下发完即收完，无需额外等待
                final long totalNs = System.nanoTime() - startNs;
                log.info("All {} messages sent and received", MESSAGE_COUNT);

                // 7. 打印结果
                printResults(MESSAGE_COUNT, totalNs, latencyHistogram);

                disruptor.shutdown();
                log.info("Disruptor shut down");
            }
        }

        log.info("Demo completed");
    }

    /**
     * Ping-pong 模式发送：每发一条消息后自旋等待 pongReceived 被 outbound-subscriber 置为 true，
     * 确保同一时刻链路中只有一条消息在途，消除队列积压对延迟测量的干扰。
     */
    private static void pingPongSend(final Publication pub,
                                     final UnsafeBuffer buf,
                                     final AtomicBoolean pongReceived,
                                     final int seqStart,
                                     final int count) {
        for (int i = 0; i < count; i++) {
            // 发送前先把 pong 标志清掉
            pongReceived.setRelease(false);

            buf.putLong(FIELD_SEQ_NO, seqStart + i);
            buf.putLong(FIELD_SEND_TS, System.nanoTime());
            long result;
            while ((result = pub.offer(buf, 0, MSG_LENGTH)) < 0) {
                if (result == Publication.CLOSED) {
                    log.error("inboundPub is CLOSED at seqNo={}, aborting", seqStart + i);
                    return;
                }
                Thread.onSpinWait();
            }

            // 自旋等待 outbound-subscriber 收到回包
            while (!pongReceived.getAcquire()) {
                Thread.onSpinWait();
            }
        }
    }

    /**
     * 等待 Publication 和 Subscription 建立连接，超时后抛出异常。
     *
     * @param pub  待检查的 Publication
     * @param sub  待检查的 Subscription
     * @param name 通道名称，用于日志区分
     * @throws InterruptedException  等待被中断
     * @throws IllegalStateException 超过 CONNECTION_TIMEOUT_MS 仍未连接
     */
    private static void waitForConnection(final Publication pub,
                                          final Subscription sub,
                                          final String name) throws InterruptedException {
        final long deadlineMs = System.currentTimeMillis() + CONNECTION_TIMEOUT_MS;
        while (!pub.isConnected() || !sub.isConnected()) {
            if (System.currentTimeMillis() > deadlineMs) {
                throw new IllegalStateException(
                        String.format("Timed out waiting for %s connection after %d ms " +
                                        "(pubConnected=%b, subConnected=%b)",
                                name, CONNECTION_TIMEOUT_MS,
                                pub.isConnected(), sub.isConnected()));
            }
            Thread.sleep(1);
        }
        log.debug("Channel '{}' connected", name);
    }

    private static void printResults(final long count,
                                     final long totalNs,
                                     final Histogram h) {
        // fix: SLF4J 不支持 {:.2f}，改用 String.format 预格式化后再传入 {}
        log.info("=== Full Pipeline (Aeron IPC + Disruptor) Results ===");
        log.info("Messages       : {}", count);
        log.info("Total time     : {} ms", String.format("%.2f", totalNs / 1e6));
        log.info("Throughput     : {} msg/s", String.format("%.0f", count / (totalNs / 1e9)));
        log.info("--- End-to-End Latency (inboundPub.offer -> outboundSub.poll) ---");
        log.info("Min            : {} ns", h.getMinValue());
        log.info("P50            : {} ns", h.getValueAtPercentile(50));
        log.info("P95            : {} ns", h.getValueAtPercentile(95));
        log.info("P99            : {} ns", h.getValueAtPercentile(99));
        log.info("P99.9          : {} ns", h.getValueAtPercentile(99.9));
        log.info("P99.99         : {} ns", h.getValueAtPercentile(99.99));
        log.info("Max            : {} ns", h.getMaxValue());
        // Phase 1 验证目标
        final long p99 = h.getValueAtPercentile(99);
        if (p99 < 1_000) {
            log.info("PASS: P99 latency {} ns < 1000 ns (1 us). Phase 1 target achieved!", p99);
        } else {
            log.warn("FAIL: P99 latency {} ns >= 1000 ns. Consider running on Linux bare-metal.", p99);
        }
    }

    // 定义事件
    static final class PipelineEvent {
        // 从 Aeron 读入原始数据，复制到 DirectBuffer
        final MutableDirectBuffer data = new UnsafeBuffer(new byte[MSG_LENGTH]);
    }

    /**
     * Disruptor 在初始化时会预先把 RingBuffer 中所有槽位的对象全部创建好（避免运行时 GC），这个创建过程就由 EventFactory 负责
     * Disruptor 在启动时会调用这个工厂方法，把 RingBuffer 里每个槽位都填充一个 PipelineEvent 对象
     */
    static final class PipelineEventFactory implements EventFactory<PipelineEvent> {
        @Override
        public PipelineEvent newInstance() {
            return new PipelineEvent();
        }
    }
}
