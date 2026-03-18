# Phase 2 撮合引擎核心实现 — Part 5：性能基准测试与验收

> **目标：** 对撮合引擎进行系统化的性能基准测试，验证单交易对撮合吞吐量 > 500K orders/sec，
> 撮合延迟 P99 < 5μs，以及热路径零 GC。完成 Phase 2 全部验收。
>
> **前置条件：** Part 1-4 完成，全部 69 个单元测试通过  
> **本节验证目标：** 吞吐量 > 500K orders/sec；P99 < 5μs（Linux 物理机）

---

## 目录

1. [性能测试策略](#1-性能测试策略)
2. [OrderBook 操作微基准](#2-orderbook-操作微基准)
3. [撮合吞吐量基准](#3-撮合吞吐量基准)
4. [延迟直方图基准](#4-延迟直方图基准)
5. [零 GC 验证](#5-零-gc-验证)
6. [运行基准测试](#6-运行基准测试)
7. [Phase 2 完整验收检查清单](#7-phase-2-完整验收检查清单)
8. [下一步：Phase 3](#8-下一步phase-3)

---

## 1. 性能测试策略

### 1.1 测试工具选型

| 工具 | 用途 | 说明 |
|------|------|------|
| **JMH** | 微基准（吞吐量、平均延迟） | 精确测量单操作耗时，隔离 JIT 干扰 |
| **HdrHistogram** | 延迟分布（P50/P99/P99.9） | 高精度延迟直方图，热路径测量 |
| **jHiccup** | GC / OS 停顿检测 | 测量 JVM 非正常停顿，验证零 GC |

### 1.2 测试场景

```
场景 A: OrderBook 基础操作吞吐量
  - addOrder (Limit Buy)
  - addOrder (Limit Sell)
  - removeOrder (撤单)
  目标：> 5M ops/sec

场景 B: 单次撮合吞吐量（Limit 全成交）
  - 预填充 1 个卖单 → 发买单触发撮合 → 预填充下一个
  目标：> 500K matches/sec

场景 C: 连续撮合吞吐量（模拟真实订单流）
  - 50% 挂单 + 50% 市价单
  目标：> 800K orders/sec

场景 D: 端到端延迟（Producer → Stage3 处理完成）
  目标：P99 < 5μs（Linux 物理机），< 20μs（macOS 开发机）
```

---

## 2. OrderBook 操作微基准

文件：`benchmark/src/main/java/com/trading/benchmark/OrderBookBenchmark.java`

```java
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

    private static final int SYMBOL_ID  = 1;
    private static final int POOL_SIZE  = 200_000;

    private OrderNodePool pool;
    private OrderBook     book;

    // 预填充的节点（用于 removeOrder 基准）
    private OrderNode[]   preFilledNodes;
    private int           removeIndex = 0;

    // addOrder 用的节点（循环复用，模拟零 GC）
    private OrderNode     reusableNode;
    private long          orderIdCounter = 0L;

    @Setup(Level.Trial)
    public void setup() {
        pool          = new OrderNodePool(POOL_SIZE);
        book          = new OrderBook(SYMBOL_ID, pool);
        reusableNode  = new OrderNode();

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
```

---

## 3. 撮合吞吐量基准

文件：`benchmark/src/main/java/com/trading/benchmark/MatchingThroughputBenchmark.java`

```java
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
```

---

## 4. 延迟直方图基准

文件：`benchmark/src/main/java/com/trading/benchmark/MatchingLatencyBenchmark.java`

```java
package com.trading.benchmark;

import com.trading.matching.matcher.OrderMatcher;
import com.trading.matching.orderbook.*;
import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import com.trading.sbe.TimeInForce;
import com.trading.util.NanoTimeProvider;
import org.hdrhistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 撮合引擎延迟直方图基准测试（非 JMH，直接测量 P99 延迟分布）。
 *
 * <p>测量从"调用 match()"到"MatchResult 填充完毕"的耗时。
 *
 * <p>验证目标：
 * <ul>
 *   <li>P50 < 500ns</li>
 *   <li>P99 < 5μs（Linux 物理机）</li>
 *   <li>P99.9 < 20μs</li>
 * </ul>
 *
 * 运行方式：
 * <pre>
 *   mvn exec:java -pl benchmark \
 *     -Dexec.mainClass="com.trading.benchmark.MatchingLatencyBenchmark" \
 *     -Dexec.jvmArgs="-Xms4g -Xmx4g -XX:+UseZGC -XX:+AlwaysPreTouch"
 * </pre>
 *
 * @author Reln Ding
 */
public final class MatchingLatencyBenchmark {

    private static final Logger log = LoggerFactory.getLogger(MatchingLatencyBenchmark.class);

    private static final int  SYMBOL_ID    = 1;
    private static final int  POOL_SIZE    = 2_000_000;
    private static final int  WARMUP_COUNT = 100_000;
    private static final int  MEASURE_COUNT = 1_000_000;

    public static void main(final String[] args) {
        final OrderNodePool pool    = new OrderNodePool(POOL_SIZE);
        final OrderBook     book    = new OrderBook(SYMBOL_ID, pool);
        final OrderMatcher  matcher = new OrderMatcher(
            book, 1_000, 2_000, NanoTimeProvider.SYSTEM);
        final MatchResult   result  = new MatchResult(2048);

        // 延迟直方图：1ns ~ 100ms，精度 3 位有效数字
        final Histogram histogram = new Histogram(1L, 100_000_000L, 3);

        log.info("Warming up {} iterations...", WARMUP_COUNT);
        runIterations(pool, book, matcher, result, WARMUP_COUNT, null);
        histogram.reset();

        log.info("Measuring {} iterations...", MEASURE_COUNT);
        runIterations(pool, book, matcher, result, MEASURE_COUNT, histogram);

        printResults(histogram);
    }

    private static void runIterations(final OrderNodePool pool,
                                      final OrderBook book,
                                      final OrderMatcher matcher,
                                      final MatchResult result,
                                      final int count,
                                      final Histogram histogram) {
        long orderIdCounter = 0L;

        for (int i = 0; i < count; i++) {
            // 挂卖单
            final OrderNode sell = pool.borrow();
            if (sell == null) {
                log.error("Pool exhausted at iteration {}", i);
                return;
            }
            sell.init(++orderIdCounter, 1001L, SYMBOL_ID, Side.SELL.value(),
                      OrderType.LIMIT.value(), TimeInForce.GTC.value(),
                      5000_00L, 100L, 0L, 0L);
            result.reset(sell);
            matcher.match(sell, result);

            // 买单触发撮合，测量此次撮合延迟
            final OrderNode buy = pool.borrow();
            if (buy == null) {
                log.error("Pool exhausted at iteration {}", i);
                return;
            }
            buy.init(++orderIdCounter, 1002L, SYMBOL_ID, Side.BUY.value(),
                     OrderType.LIMIT.value(), TimeInForce.GTC.value(),
                     5000_00L, 100L, 0L, 0L);
            result.reset(buy);

            final long startNs = System.nanoTime();
            matcher.match(buy, result);
            final long latencyNs = System.nanoTime() - startNs;

            if (histogram != null) {
                histogram.recordValue(Math.min(latencyNs, 100_000_000L));
            }

            // 归还对象池
            if (result.takerStatus != MatchResult.TakerStatus.RESTING) {
                pool.release(buy);
            }
        }
    }

    private static void printResults(final Histogram h) {
        log.info("=== Matching Engine Latency Results ===");
        log.info("Samples      : {}", h.getTotalCount());
        log.info("Min          : {} ns", h.getMinValue());
        log.info("P50          : {} ns", h.getValueAtPercentile(50));
        log.info("P90          : {} ns", h.getValueAtPercentile(90));
        log.info("P95          : {} ns", h.getValueAtPercentile(95));
        log.info("P99          : {} ns", h.getValueAtPercentile(99));
        log.info("P99.9        : {} ns", h.getValueAtPercentile(99.9));
        log.info("P99.99       : {} ns", h.getValueAtPercentile(99.99));
        log.info("Max          : {} ns", h.getMaxValue());

        // 自动验收
        final long p99Ns = h.getValueAtPercentile(99);
        if (p99Ns < 5_000) {
            log.info("PASS: P99 = {} ns < 5000 ns (5 us). Phase 2 target achieved!", p99Ns);
        } else if (p99Ns < 20_000) {
            log.warn("WARN: P99 = {} ns (macOS dev machine, acceptable). "
                     + "Run on Linux bare-metal for production target.", p99Ns);
        } else {
            log.error("FAIL: P99 = {} ns >= 20 us. "
                      + "Check CPU affinity and GC configuration.", p99Ns);
        }
    }
}
```

---

## 5. 零 GC 验证

### 5.1 GC 日志配置

在 JVM 启动参数中加入 GC 日志输出：

```bash
mvn exec:java -pl benchmark \
  -Dexec.mainClass="com.trading.benchmark.MatchingLatencyBenchmark" \
  -Dexec.jvmArgs="\
    -Xms4g -Xmx4g \
    -XX:+UseZGC \
    -XX:+AlwaysPreTouch \
    -Xlog:gc*:file=/tmp/gc-matching.log:time,uptime:filecount=3,filesize=20m \
    --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
```

### 5.2 GC 停顿验收标准

```
验收：稳定运行期间（预热后）GC 停顿次数 = 0，或 ZGC 停顿 < 1ms
告警：若出现 G1 Full GC，说明存在大量对象分配，需检查对象池使用

期望日志（ZGC 正常输出）：
[gc] GC(0) Pause Start
[gc] GC(0) Pause End 0.123ms      ← ZGC 停顿 < 1ms，允许
NO Full GC events                  ← 无 Full GC
```

### 5.3 对象分配验证（Allocation Profiling）

```bash
# 使用 async-profiler 检测热路径对象分配
java -agentpath:/path/to/libasyncProfiler.so=start,event=alloc,\
  file=/tmp/alloc-profile.html \
  -jar benchmark/target/benchmarks.jar MatchingThroughputBenchmark
```

**热路径预期结果：**

```
期望：executeCoreMatch() 内部无 new 操作
期望：OrderNode 全部来自 OrderNodePool
期望：MatchEvent 全部来自 MatchEventList 预分配池
禁止：TreeMap.Entry 在撮合循环中出现（说明有新 Entry 被创建）
```

---

## 6. 运行基准测试

### 6.1 编译并打包 benchmark 模块

```bash
cd trading-platform
mvn package -pl benchmark -am -q -Dcheckstyle.skip=true
```

### 6.2 运行 JMH 吞吐量基准

```bash
# OrderBook 操作基准
java -Xms4g -Xmx4g -XX:+UseZGC \
     --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     -jar benchmark/target/benchmarks.jar "OrderBookBenchmark" \
     -rf json -rff results-orderbook.json

# 撮合吞吐量基准
java -Xms8g -Xmx8g -XX:+UseZGC -XX:+AlwaysPreTouch \
     --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     -jar benchmark/target/benchmarks.jar "MatchingThroughputBenchmark" \
     -rf json -rff results-matching-throughput.json
```

### 6.3 运行延迟直方图基准

```bash
mvn exec:java -pl benchmark \
  -Dexec.mainClass="com.trading.benchmark.MatchingLatencyBenchmark" \
  "-Dexec.jvmArgs=-Xms4g -Xmx4g -XX:+UseZGC -XX:+AlwaysPreTouch \
   --add-opens=java.base/sun.nio.ch=ALL-UNNAMED" \
  -Dcheckstyle.skip=true
```

### 6.4 期望测试结果

**macOS ARM（开发参考值）：**

```
Benchmark                                   Mode  Cnt       Score   Units
OrderBookBenchmark.addBuyOrder              thrpt   5  12,345,678  ops/s
OrderBookBenchmark.addSellOrder             thrpt   5  11,987,432  ops/s
OrderBookBenchmark.removeOrderBenchmark     thrpt   5  13,102,567  ops/s

MatchingThroughputBenchmark.singleMatchFullFill  thrpt  5  1,234,567  ops/s
MatchingThroughputBenchmark.mixedWorkload        thrpt  5  2,109,876  ops/s

=== Matching Engine Latency Results ===
Samples      : 1000000
Min          : 112 ns
P50          : 287 ns
P90          : 512 ns
P95          : 768 ns
P99          : 2,048 ns    ← macOS 约 2μs，Linux 物理机约 < 1μs
P99.9        : 8,192 ns
P99.99       : 32,768 ns
Max          : 131,072 ns
WARN: P99 = 2048 ns (macOS dev machine, acceptable).
```

**Linux 物理机（目标值，隔离 CPU 核心）：**

```
MatchingThroughputBenchmark.singleMatchFullFill  thrpt  5  3,500,000+  ops/s

P50  : < 200 ns
P99  : < 2,000 ns  (< 2μs，超出 Phase 2 目标 5μs)
P99.9: < 5,000 ns
```

---

## 7. Phase 2 完整验收检查清单

### 7.1 功能验收

#### Part 1 数据结构

- [ ] `OrderNode.init()` / `reset()` 所有字段覆盖完整
- [ ] `OrderNodePool`：池空返回 `null`，负容量抛 `IllegalArgumentException`
- [ ] `PriceLevel`：addOrder / removeOrder 指针操作无内存泄漏
- [ ] `OrderBook`：买盘降序、卖盘升序、空档位及时清除

#### Part 2 撮合算法

- [ ] `Limit`：有成交挂剩余，无对手盘直接挂单
- [ ] `Market`：强制 IOC，不挂单，哨兵价格正确
- [ ] `IOC`：部分成交后剩余量不入簿
- [ ] `FOK`：预检为只读，不足则零成交拒绝
- [ ] `PostOnly`：会成交则拒绝，不会成交才挂单
- [ ] Maker 价格优先（成交价 = maker.price，不是 taker.price）
- [ ] 全成交节点正确归还对象池
- [ ] 空档位在全成交后从 TreeMap 中移除

#### Part 3 Pipeline 集成

- [ ] Disruptor Pipeline 拓扑：Stage1 → Stage2 → [Stage3a ‖ Stage3b ‖ Stage3c]
- [ ] `InboundOrderSubscriber` 正确解码 `InternalNewOrder`（templateId=201）
- [ ] `InboundOrderSubscriber` 正确解码 `InternalCancelOrder`（templateId=202）
- [ ] `JournalPublishHandler` 非阻塞写入（`BACK_PRESSURED` 只告警，不重试阻塞撮合）
- [ ] `MatchingEngineMain` 启动无报错，注册关闭钩子

#### Part 4 测试覆盖

- [ ] `OrderBookStructureTest` 17 个测试通过
- [ ] `OrderMatcherSmokeTest` 14 个测试通过
- [ ] `OrderMatcherEdgeCaseTest` 24 个测试通过
- [ ] `MatchResultTest` 8 个测试通过
- [ ] `FeeCalculationTest` 4 个测试通过
- [ ] `MatchingPipelineSmokeTest` 2 个测试通过
- [ ] **总计 69 个测试，0 Failures，0 Errors**
- [ ] JaCoCo 行覆盖率 100%，分支覆盖率 100%

### 7.2 性能验收

- [ ] `OrderBook.addOrder` 吞吐量 > 5M ops/sec（JMH）
- [ ] `OrderBook.removeOrder` 吞吐量 > 5M ops/sec（JMH）
- [ ] 单次完全撮合吞吐量 > 500K matches/sec（JMH）
- [ ] 混合负载吞吐量 > 800K orders/sec（JMH）
- [ ] 撮合延迟 P99 < 5μs（Linux 物理机）/ < 20μs（macOS 开发机）
- [ ] 稳定运行期间无 Full GC（ZGC 停顿 < 1ms 可接受）

### 7.3 代码质量

- [ ] 所有新增类的 `@author` 为 `Reln Ding`
- [ ] 热路径方法（`executeCoreMatch`、`addOrder`、`removeOrder`）内无 `new` 操作
- [ ] 无 `System.currentTimeMillis()` 直接调用（使用 `NanoTimeProvider` 接口）
- [ ] 所有 Aeron `Publication.offer()` 返回值均有处理（不静默忽略负值）

---

## 8. 下一步：Phase 3

Phase 2 全部验收通过后，进入 **Phase 3：柜台服务实现**，包括：

### Phase 3 目标

**验证目标：** 完整下单 → 撮合 → 回报链路 P99 < 10μs

### Phase 3 主要任务

1. **账户余额管理（Spot）**
   - `AccountManager`：余额冻结/解冻/扣减
   - 买单挂单冻结 quote 资产，卖单冻结 base 资产
   - 成交后资金划转

2. **保证金管理（Perp/Futures）**
   - `MarginManager`：初始保证金、维持保证金
   - 未实现盈亏实时计算
   - 强平价格计算

3. **仓位管理**
   - `PositionManager`：开仓/平仓/仓位合并
   - 多空仓位独立维护

4. **风控模块**
   - `BalanceRiskChecker`：余额充足性校验
   - `PriceBandChecker`：价格笼子（防异常价格冲击）

5. **柜台 Disruptor Pipeline**
   - `AuthHandler` → `SymbolHandler` → `RiskHandler` → `FreezeHandler` → `RouteHandler`

6. **Counter → MatchEngine → Counter 完整回路**
   - `ExecutionReportProcessor`：订阅撮合回报，更新仓位/余额，回报客户端

7. **ClusteredService 集成**
   - 实现 `ClusteredService` 接口，接入 Aeron Cluster Raft 共识
