package com.trading.matching;

import com.trading.matching.disruptor.MatchingDisruptor;
import com.trading.matching.matcher.OrderMatcher;
import com.trading.matching.orderbook.OrderBook;
import com.trading.matching.orderbook.OrderNodePool;
import com.trading.util.NanoTimeProvider;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 撮合引擎启动入口。
 *
 * <p>生产环境启动参数（参考）：
 * <pre>
 * java -server
 *      -Xms64g -Xmx64g
 *      -XX:+UseZGC
 *      -XX:+AlwaysPreTouch
 *      -Daeron.dir=/dev/shm/aeron
 *      -Dmatching.symbol.ids=1,2,3
 *      com.trading.matching.MatchingEngineMain
 * </pre>
 *
 * @author Reln Ding
 */
public class MatchingEngineMain {
    private static final Logger log = LoggerFactory.getLogger(MatchingEngineMain.class);

    // Aeron 通道配置
    private static final String IPC_CHANNEL = "aeron:ipc";
    private static final int INBOUND_STREAM = 2;   // Counter → ME
    private static final int EXEC_REPORT_STREAM = 3;  // ME → Counter（成交回报）
    private static final int MARKET_DATA_STREAM = 4;  // ME → Push（行情）
    private static final int JOURNAL_STREAM = 5;  // ME → Journal（日志）

    // 交易对配置（生产环境从配置文件加载）
    private static final int DEFAULT_SYMBOL_ID = 1;
    private static final int DEFAULT_MAKER_FEE = 1_000;   // 0.1%
    private static final int DEFAULT_TAKER_FEE = 2_000;   // 0.2%
    private static final int DEFAULT_POOL_SIZE = 500_000;

    public static void main(final String[] args) throws Exception {
        final String aeronDir = System.getProperty("aeron.dir", "/tmp/aeron-matching");

        log.info("Starting Matching Engine, aeronDir={}", aeronDir);

        // 1. 启动 Embedded Media Driver
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(false)   // 生产环境不删除，保留日志
                .threadingMode(ThreadingMode.DEDICATED)
                .conductorIdleStrategy(new BusySpinIdleStrategy())
                .senderIdleStrategy(new BusySpinIdleStrategy())
                .receiverIdleStrategy(new BusySpinIdleStrategy())
                .aeronDirectoryName(aeronDir);

        final MediaDriver driver = MediaDriver.launch(driverCtx);

        // 2. 连接 Aeron
        final Aeron aeron = Aeron.connect(
                new Aeron.Context().aeronDirectoryName(aeronDir));

        // 3. 创建 Aeron 通道
        final Subscription inboundSub = aeron.addSubscription(IPC_CHANNEL, INBOUND_STREAM);
        final Publication execPub = aeron.addPublication(IPC_CHANNEL, EXEC_REPORT_STREAM);
        final Publication mktPub = aeron.addPublication(IPC_CHANNEL, MARKET_DATA_STREAM);
        final Publication jrnlPub = aeron.addPublication(IPC_CHANNEL, JOURNAL_STREAM);

        // 4. 初始化 OrderBook 和 OrderMatcher
        final OrderNodePool pool = new OrderNodePool(DEFAULT_POOL_SIZE);
        final OrderBook book = new OrderBook(DEFAULT_SYMBOL_ID, pool);
        final OrderMatcher matcher = new OrderMatcher(
                book, DEFAULT_MAKER_FEE, DEFAULT_TAKER_FEE, NanoTimeProvider.SYSTEM);

        // 5. 启动 Disruptor Pipeline
        final MatchingDisruptor matchingDisruptor = new MatchingDisruptor(
                book, matcher, inboundSub, jrnlPub, execPub, mktPub);

        log.info("Matching Engine started successfully.");

        // 6. 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Matching Engine...");
            matchingDisruptor.shutdown();
            aeron.close();
            driver.close();
            log.info("Matching Engine shutdown complete.");
        }));

        // 7. 主线程等待（实际由 Disruptor 工作线程驱动）
        Thread.currentThread().join();
    }
}
