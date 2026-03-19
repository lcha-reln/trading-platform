package com.trading.benchmark;

import com.trading.matching.orderbook.OrderBook;
import com.trading.matching.orderbook.OrderNode;
import com.trading.matching.orderbook.OrderNodePool;
import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import com.trading.sbe.TimeInForce;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * OrderBook 基础操作微基准测试。
 *
 * <p>验证目标：
 * <ul>
 *   <li>addOrder (no match)：> 10M ops/sec</li>
 *   <li>removeOrder：> 10M ops/sec</li>
 * </ul>
 *
 * @author Reln Ding
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {
        "-Xms4g", "-Xmx4g",
        "-XX:+UseZGC",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
})
public class OrderBookBenchmark {

    private static final int SYMBOL_ID = 1;
    private static final int POOL_SIZE = 200_000;

    private OrderNodePool pool;
    private OrderBook book;

    // 预填充的节点（用于 removeOrder 基准）
    private OrderNode[] preFilledNodes;
    private int removeIndex = 0;

    // addOrder 用的节点（循环复用，模拟零 GC）
    private OrderNode reusableNode;
    private long orderIdCounter = 0L;

    @Setup(Level.Trial)
    public void setup() {
        pool = new OrderNodePool(POOL_SIZE);
        book = new OrderBook(SYMBOL_ID, pool);
        reusableNode = new OrderNode();

        // 预填充 10 万个卖单，用于 removeOrder 基准
        preFilledNodes = new OrderNode[100_000];
        for (int i = 0; i < preFilledNodes.length; i++) {
            final OrderNode n = pool.borrow();
            n.init(i + 1L, 1001L, SYMBOL_ID, Side.SELL.value(),
                    OrderType.LIMIT.value(), TimeInForce.GTC.value(),
                    5000_00L + i * 100L,  // 不同价格，避免同价位链表影响
                    100L, 0L, System.nanoTime());
            book.addOrder(n);
            preFilledNodes[i] = n;
        }
    }

    // ---- addOrder 基准 ----

    @Benchmark
    public void addBuyOrder() {
        reusableNode.init(++orderIdCounter, 1002L, SYMBOL_ID,
                Side.BUY.value(),
                OrderType.LIMIT.value(), TimeInForce.GTC.value(),
                4000_00L + (orderIdCounter % 1000) * 100L,  // 买价远低于卖价，不触发撮合
                100L, 0L, 0L);
        book.addOrder(reusableNode);
        // 立即撤出，防止订单簿无限增长（模拟零 GC 复用）
        book.removeOrder(reusableNode.orderId);
    }

    @Benchmark
    public void addSellOrder() {
        reusableNode.init(++orderIdCounter, 1001L, SYMBOL_ID,
                Side.SELL.value(),
                OrderType.LIMIT.value(), TimeInForce.GTC.value(),
                9000_00L + (orderIdCounter % 1000) * 100L,  // 卖价远高于买价
                100L, 0L, 0L);
        book.addOrder(reusableNode);
        book.removeOrder(reusableNode.orderId);
    }

    // ---- removeOrder 基准（从预填充的订单中撤单）----

    @Benchmark
    public OrderNode removeOrderBenchmark() {
        if (removeIndex >= preFilledNodes.length) {
            removeIndex = 0;
            // 重新填充（避免索引越界）
            for (int i = 0; i < preFilledNodes.length; i++) {
                if (book.getOrder(preFilledNodes[i].orderId) == null) {
                    final OrderNode n = pool.borrow();
                    if (n != null) {
                        n.init(preFilledNodes[i].orderId, 1001L, SYMBOL_ID,
                                Side.SELL.value(), OrderType.LIMIT.value(),
                                TimeInForce.GTC.value(),
                                5000_00L + i * 100L, 100L, 0L, 0L);
                        book.addOrder(n);
                        preFilledNodes[i] = n;
                    }
                }
            }
        }
        final OrderNode removed = book.removeOrder(preFilledNodes[removeIndex++].orderId);
        if (removed != null) {
            pool.release(removed);
        }
        return removed;
    }

    public static void main(final String[] args) throws Exception {
        new Runner(new OptionsBuilder()
                .include(OrderBookBenchmark.class.getSimpleName())
                .build()).run();
    }
}
