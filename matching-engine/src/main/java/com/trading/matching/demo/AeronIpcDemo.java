package com.trading.matching.demo;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aeron IPC 基础链路 Demo。
 * <p>
 * 演示：
 * 1. 在同一进程内启动 Embedded Media Driver
 * 2. 创建 Publication 和 Subscription（IPC 通道）
 * 3. Publisher 线程持续发送消息
 * 4. Subscriber 线程持续接收消息
 * 5. 统计吞吐量和确认消息完整性
 * <p>
 * 运行方式：
 * mvn exec:java -pl matching-engine \
 * -Dexec.mainClass="com.trading.matching.demo.AeronIpcDemo" \
 * -Daeron.dir=/tmp/aeron
 */
public class AeronIpcDemo {
    private static final Logger log = LoggerFactory.getLogger(AeronIpcDemo.class);

    // IPC 通道（同进程内，零拷贝共享内存）
    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1;

    // Demo 参数
    private static final int MESSAGE_COUNT = 1_000_000;  // 发送 100 万条消息
    private static final int MESSAGE_LENGTH = 64;          // 每条消息 64 字节
    private static final long WARMUP_COUNT = 10_000;      // 预热 1 万条

    public static void main(final String[] args) throws Exception {
        // 1. 配置并启动 Embedded Media Driver
        //    DEDICATED 模式：Conductor/Sender/Receiver 各自独立线程
        //    SHARED 模式：所有角色共用一个线程（低延迟场景不推荐）
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
                .dirDeleteOnStart(true)           // 启动时清理旧的 aeron 目录
                .dirDeleteOnShutdown(true)        // 关闭时清理
                .threadingMode(ThreadingMode.DEDICATED)  // 专用线程模式
                .conductorIdleStrategy(new BusySpinIdleStrategy())   // Conductor 忙轮询
                .senderIdleStrategy(new BusySpinIdleStrategy())      // Sender 忙轮询
                .receiverIdleStrategy(new BusySpinIdleStrategy())    // Receiver 忙轮询
                .aeronDirectoryName(System.getProperty("aeron.dir", "/tmp/aeron-demo"));

        log.info("Starting Aeron Media Driver at: {}", driverCtx.aeronDirectoryName());

        try (MediaDriver driver = MediaDriver.launch(driverCtx);) {
            // 2. 创建 Aeron 客户端
            final Aeron.Context aeronCtx = new Aeron.Context()
                    .aeronDirectoryName(driverCtx.aeronDirectoryName());

            try (Aeron aeron = Aeron.connect(aeronCtx)) {
                Publication publication = aeron.addPublication(CHANNEL, STREAM_ID);
                Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID);

                // 等待 Publication 和 Subscription 连接
                waitForConnection(publication, subscription);
                log.info("Publication and Subscription connected. Starting demo...");

                // 3.启动 Subscriber
                // 3.1.创建一个安全的计数器，初始值为0，用于统计 subscriber 已收到多少条消息
                final AtomicLong receivedCount = new AtomicLong(0);
                // 3.2.创建一个"倒计时门闩"，计数初始值为 1
                // 作用是让主线程等待 Subscriber 完成。当 Subscriber 收完所有消息后调用 doneLatch.countDown()，计数变为0
                // 主线程的 doneLatch().await() 就会解除阻塞继续执行，相当于一个单次的完成信号
                final CountDownLatch doneLatch = new CountDownLatch(1);

                final Thread subscriberThread = new Thread(() ->
                        runSubscriber(subscription, receivedCount, MESSAGE_COUNT, doneLatch),
                        "subscriber-thread"
                );
                subscriberThread.setDaemon(true);
                subscriberThread.start();

                // 4.预热（避免 JIT 编译影响测量结果）
                final UnsafeBuffer sendBuffer = new UnsafeBuffer(new byte[MESSAGE_LENGTH]);
                log.info("Warming up with {} messages...", WARMUP_COUNT);

                for (int i = 0; i < WARMUP_COUNT; i++) {
                    sendBuffer.putLong(0, i);
                    sendMessage(publication, sendBuffer, MESSAGE_LENGTH);
                }

                // 5.正式发送消息
                final long startNs = System.nanoTime();

                for (int i = 0; i < MESSAGE_COUNT; i++) {
                    sendBuffer.putLong(0, i);
                    sendBuffer.putLong(8, System.nanoTime());
                    sendMessage(publication, sendBuffer, MESSAGE_LENGTH);
                }

                final long sendEndNs = System.nanoTime();

                // 6.等待所有消息被消费
                doneLatch.await();
                final long totalNs = System.nanoTime() - startNs;

                // 7.打印统计结果
                printStats(MESSAGE_COUNT, totalNs, sendEndNs - startNs);
            }
        }
    }

    private static void printStats(int msgCount, long totalNs, long sendOnlyNs) {
        final double totalMs = totalNs / 1e6;
        final double sendMs = sendOnlyNs / 1e6;
        final double tps = msgCount / (totalNs / 1e9);
        final double avgNs = (double) totalNs / msgCount;

        log.info("=== Aeron IPC Demo Results ===");
        log.info("Messages       : {}", msgCount);
        log.info("Total time     : {} ms", String.format("%.2f", totalMs));
        log.info("Send time      : {} ms", String.format("%.2f", sendMs));
        log.info("Throughput     : {} msg/sec", String.format("%.0f", tps));
        log.info("Avg latency    : {} ns/msg", String.format("%.2f", avgNs));
    }

    /**
     * 发送单条消息，处理背压（BACK_PRESSURED）和管理员动作（ADMIN_ACTION）。
     */
    private static void sendMessage(Publication publication, UnsafeBuffer sendBuffer, int messageLength) {
        long result;
        // 忙等待直到发送成功（热路径，使用忙轮训）
        while ((result = publication.offer(sendBuffer, 0, messageLength)) < 0) {
            if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED) {
                throw new RuntimeException("Publication not available: " + result);
            }

            // BACK_PRESSURED 或 ADMIN_ACTION：继续重试
            Thread.onSpinWait();  // Java 9+ CPU hint，提示处理器当前在自旋等待
        }
    }

    /**
     * Subscriber 持续轮询消息直到收到指定数量。
     */
    private static void runSubscriber(Subscription subscription, AtomicLong receivedCount, int messageCount, CountDownLatch doneLatch) {
        final IdleStrategy idleStrategy = new BusySpinIdleStrategy();

        // FragmentHandler：每次收到一个 Fragment 时调用
        final FragmentHandler handler = (buffer, offset, length, header) -> {
            // 这里是消息处理热路径
            // buffer：包含消息数据的 DirectBuffer（Aeron 管理，不要持有引用）
            // offset：消息在 buffer 中的起始位置
            // length：消息长度
            final long seqNo = buffer.getLong(offset);      // 读取序列号（零拷贝）
            final long sendTs = buffer.getLong(offset + 8); // 读取发送时间戳

            final long count = receivedCount.incrementAndGet();
            if (count == messageCount) {
                doneLatch.countDown();
            }
        };

        long count = receivedCount.get();
        while (count < messageCount) {
            // poll：尝试读取最多 10 个 Fragment，返回实际读取数量
            final int fragmentRead = subscription.poll(handler, 10);
            idleStrategy.idle(fragmentRead);
            count = receivedCount.get();
        }
    }

    /**
     * 等待 Publication 和 Subscription 建立连接。
     */
    private static void waitForConnection(Publication publication, Subscription subscription) throws InterruptedException {
        final long timeoutMs = 5000;
        final long deadline = System.currentTimeMillis() + timeoutMs;

        while (!publication.isConnected() || !subscription.isConnected()) {
            if (System.currentTimeMillis() > deadline) {
                throw new RuntimeException("Connection timeout after " + timeoutMs + "ms");
            }

            Thread.sleep(10);
        }
    }
}
