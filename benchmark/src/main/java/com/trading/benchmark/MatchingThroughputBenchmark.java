package com.trading.benchmark;

import com.trading.matching.matcher.OrderMatcher;
import com.trading.matching.orderbook.*;
import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import com.trading.sbe.TimeInForce;
import com.trading.util.NanoTimeProvider;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * 撮合引擎吞吐量基准测试。
 *
 * <p>验证目标：
 * <ul>
 *   <li>单次完全撮合（1 Maker + 1 Taker）：> 500K matches/sec</li>
 *   <li>混合负载（50% 挂单 + 50% 市价成交）：> 800K orders/sec</li>
 * </ul>
 *
 * @author Reln Ding
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgsAppend = {
        "-Xms8g", "-Xmx8g",
        "-XX:+UseZGC",
        "-XX:+AlwaysPreTouch",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
})
public class MatchingThroughputBenchmark {

    private static final int SYMBOL_ID        = 1;
    private static final int MAKER_FEE_MICROS = 1_000;
    private static final int TAKER_FEE_MICROS = 2_000;
    private static final int POOL_SIZE        = 1_000_000;

    private OrderNodePool pool;
    private OrderBook     book;
    private OrderMatcher  matcher;
    private MatchResult   result;

    private long orderIdCounter = 0L;

    @Setup(Level.Trial)
    public void setup() {
        pool    = new OrderNodePool(POOL_SIZE);
        book    = new OrderBook(SYMBOL_ID, pool);
        matcher = new OrderMatcher(book, MAKER_FEE_MICROS, TAKER_FEE_MICROS,
                NanoTimeProvider.SYSTEM);
        result  = new MatchResult(2048);
    }

    // ---- 场景 B: 单次完全撮合 ----
    // 预置 1 个卖单 → 买单进来全成交 → 循环

    @Benchmark
    public MatchResult.TakerStatus singleMatchFullFill() {
        // Step 1: 挂一个卖单
        final OrderNode sell = pool.borrow();
        sell.init(++orderIdCounter, 1001L, SYMBOL_ID, Side.SELL.value(),
                OrderType.LIMIT.value(), TimeInForce.GTC.value(),
                5000_00L, 100L, 0L, 0L);
        result.reset(sell);
        matcher.match(sell, result);

        // Step 2: 买单进来，触发完全撮合
        final OrderNode buy = pool.borrow();
        buy.init(++orderIdCounter, 1002L, SYMBOL_ID, Side.BUY.value(),
                OrderType.LIMIT.value(), TimeInForce.GTC.value(),
                5000_00L, 100L, 0L, 0L);
        result.reset(buy);
        matcher.match(buy, result);

        // 归还已成交节点到对象池
        if (result.takerStatus != MatchResult.TakerStatus.RESTING) {
            pool.release(buy);
        }

        return result.takerStatus;
    }

    // ---- 场景 C: 混合负载（50% Limit 挂单 + 50% Market 消耗）----

    private boolean addPhase = true;  // 交替挂单/吃单

    @Benchmark
    public MatchResult.TakerStatus mixedWorkload() {
        if (addPhase) {
            // 挂一个限价卖单
            final OrderNode sell = pool.borrow();
            if (sell == null) return null;
            sell.init(++orderIdCounter, 1001L, SYMBOL_ID, Side.SELL.value(),
                    OrderType.LIMIT.value(), TimeInForce.GTC.value(),
                    5000_00L, 100L, 0L, 0L);
            result.reset(sell);
            matcher.match(sell, result);
            addPhase = false;
            return result.takerStatus;
        } else {
            // 市价买单吃掉对应卖单
            final OrderNode buy = pool.borrow();
            if (buy == null) return null;
            buy.init(++orderIdCounter, 1002L, SYMBOL_ID, Side.BUY.value(),
                    OrderType.MARKET.value(), TimeInForce.GTC.value(),
                    0L, 100L, 0L, 0L);
            result.reset(buy);
            matcher.match(buy, result);

            if (result.takerStatus != MatchResult.TakerStatus.RESTING) {
                pool.release(buy);
            }
            addPhase = true;
            return result.takerStatus;
        }
    }

    public static void main(final String[] args) throws Exception {
        new Runner(new OptionsBuilder()
                .include(MatchingThroughputBenchmark.class.getSimpleName())
                .build()).run();
    }
}
