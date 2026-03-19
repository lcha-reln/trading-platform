# Phase 4 Gateway 与推送服务 — Part 5：全量测试、端到端延迟基准与验收清单

> **目标：** 运行 Phase 4 所有模块的全量测试，执行端到端延迟基准测试，
> 完成 Phase 4 及整个项目（Phase 1-4）的验收。
>
> **前置条件：** Part 1-4 完成，累计 72 个测试通过  
> **本节验证目标：** 端到端 P99 < 1ms；全量测试 0 Failures；完成项目验收

---

## 目录

1. [全量测试汇总](#1-全量测试汇总)
2. [端到端延迟基准](#2-端到端延迟基准)
3. [Gateway 吞吐量基准](#3-gateway-吞吐量基准)
4. [Journal 写入吞吐量基准](#4-journal-写入吞吐量基准)
5. [Phase 4 完整验收检查清单](#5-phase-4-完整验收检查清单)
6. [Phase 1-4 项目总验收](#6-phase-1-4-项目总验收)

---

## 1. 全量测试汇总

### 1.1 各模块测试命令

```bash
cd trading-platform

# gateway-service
mvn test -pl gateway-service -Dcheckstyle.skip=true

# push-service
mvn test -pl push-service -Dcheckstyle.skip=true

# journal-service
mvn test -pl journal-service -Dcheckstyle.skip=true

# 一键运行 Phase 4 三个模块
mvn test -pl gateway-service,push-service,journal-service -Dcheckstyle.skip=true
```

### 1.2 Phase 4 测试清单

| Part           | 测试文件                                               | 测试数        |
|----------------|----------------------------------------------------|------------|
| Part 1         | `AuthenticatorTest` + `TokenBucketRateLimiterTest` | 22         |
| Part 2         | `HandlerTest`（含 SessionRegistry）                   | 18         |
| Part 3         | `PushDispatcherTest`                               | 18         |
| Part 4         | `JournalWriterTest`                                | 14         |
| **Phase 4 合计** |                                                    | **72 个测试** |

### 1.3 全项目测试汇总

```bash
# 运行所有模块全量测试
mvn test -Dcheckstyle.skip=true
```

期望输出：

```
-------------------------------------------------------
 T E S T S (各模块汇总)
-------------------------------------------------------
[common-util]        Tests run:  5, Failures: 0, Errors: 0
[matching-engine]    Tests run: 69, Failures: 0, Errors: 0
[counter-service]    Tests run: 107, Failures: 0, Errors: 0
[gateway-service]    Tests run: 72, Failures: 0, Errors: 0
[push-service]       Tests run: 18, Failures: 0, Errors: 0
[journal-service]    Tests run: 14, Failures: 0, Errors: 0
─────────────────────────────────────────────────────
Total:               Tests run: 285, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
```

---

## 2. 端到端延迟基准

### 2.1 测量链路说明

```
[Client]
  │  HTTP POST /api/v1/order（本地回环，消除网络RTT）
  ▼
[Gateway HttpServerHandler]
  │  JWT 验签 + 限流 + SBE 编码
  ▼
[AeronClusterClient] → Aeron UDP → Cluster Ingress
  ▼
[CounterClusteredService] → Disruptor Pipeline（5段）
  ▼
[MatchingEngine] → OrderMatcher.match()
  ▼
[ExecutionReportHandler] → Aeron IPC stream=3 → Counter
  ▼
[ExecutionReportProcessor] → SBE ExecutionReport → Cluster Egress
  ▼
[GatewayEgressListener] → WebSocket TextWebSocketFrame
  ▼
[Client WebSocket 接收回报]  ← 测量终点
```

**测量方法：** 在 Client 侧记录 `sendTimestampNs = System.nanoTime()`，
收到 WebSocket 回报后记录 `recvTimestampNs = System.nanoTime()`，
`latency = recvTimestampNs - sendTimestampNs`。

### 2.2 端到端延迟基准程序

文件：`benchmark/src/main/java/com/trading/benchmark/EndToEndLatencyBenchmark.java`

```java
package com.trading.benchmark;

import com.trading.gateway.auth.JwtAuthenticator;
import org.hdrhistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 端到端延迟基准测试。
 *
 * <p>测量从"HTTP 下单请求发出"到"收到 WebSocket ExecutionReport 回报"的延迟。
 *
 * <p>运行前提：
 * <ul>
 *   <li>Gateway 运行在 localhost:8080</li>
 *   <li>Aeron Cluster 已启动</li>
 *   <li>测试账户已充值足够余额</li>
 * </ul>
 *
 * <p>运行方式：
 * <pre>
 *   mvn exec:java -pl benchmark \
 *     -Dexec.mainClass="com.trading.benchmark.EndToEndLatencyBenchmark" \
 *     -Dexec.args="localhost 8080 1001 100000"
 * </pre>
 *
 * @author Reln Ding
 */
public final class EndToEndLatencyBenchmark {

    private static final Logger log = LoggerFactory.getLogger(EndToEndLatencyBenchmark.class);

    private static final int  WARMUP_COUNT  = 1_000;
    private static final int  MEASURE_COUNT = 10_000;

    public static void main(final String[] args) throws Exception {
        final String host      = args.length > 0 ? args[0] : "localhost";
        final int    port      = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        final long   accountId = args.length > 2 ? Long.parseLong(args[2]) : 1001L;
        final int    symbolId  = args.length > 3 ? Integer.parseInt(args[3]) : 1;

        final String jwtSecret = System.getProperty("gateway.jwt.secret",
                                                     "dev-secret-key-32-bytes-minimum!!!");
        final JwtAuthenticator jwtAuth = new JwtAuthenticator(jwtSecret);
        final String token = jwtAuth.generate(accountId, 3600L);

        final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        final Histogram histogram = new Histogram(1L, 10_000_000_000L, 3);
        final AtomicLong sendTs   = new AtomicLong();
        final AtomicInteger received = new AtomicInteger();

        log.info("Connecting to ws://{}:{}/ws ...", host, port);

        // WS 客户端（使用 Java 11 HttpClient WebSocket API）
        final CountDownLatch wsReady = new CountDownLatch(1);
        final CountDownLatch done    = new CountDownLatch(1);
        final int totalCount         = WARMUP_COUNT + MEASURE_COUNT;

        final java.net.http.WebSocket wsClient = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI.create("ws://" + host + ":" + port + "/ws"),
                new java.net.http.WebSocket.Listener() {
                    @Override
                    public void onOpen(final java.net.http.WebSocket ws) {
                        // 鉴权
                        ws.sendText("{\"action\":\"auth\",\"token\":\"" + token + "\"}", true);
                        ws.request(Long.MAX_VALUE);
                        wsReady.countDown();
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<?> onText(
                        final java.net.http.WebSocket ws,
                        final CharSequence data, final boolean last) {
                        final String msg = data.toString();
                        if (msg.contains("\"e\":\"executionReport\"")) {
                            final long latency = System.nanoTime() - sendTs.get();
                            final int cnt = received.incrementAndGet();
                            if (cnt > WARMUP_COUNT) {
                                histogram.recordValue(Math.min(latency, 10_000_000_000L));
                            }
                            if (cnt >= totalCount) done.countDown();
                        }
                        return null;
                    }
                })
            .join();

        if (!wsReady.await(5, TimeUnit.SECONDS)) {
            log.error("WS connection timeout");
            return;
        }

        // 构造下单 JSON
        final String orderJson = buildOrderJson(symbolId);
        final String baseUrl   = "http://" + host + ":" + port + "/api/v1/order";

        log.info("Warming up {} iterations...", WARMUP_COUNT);
        for (int i = 0; i < WARMUP_COUNT; i++) {
            sendTs.set(System.nanoTime());
            sendOrder(httpClient, baseUrl, token, orderJson);
            Thread.sleep(1);  // 避免过快导致限流
        }

        log.info("Measuring {} iterations...", MEASURE_COUNT);
        final long startNs = System.nanoTime();
        for (int i = 0; i < MEASURE_COUNT; i++) {
            sendTs.set(System.nanoTime());
            sendOrder(httpClient, baseUrl, token, orderJson);
            Thread.sleep(1);
        }

        if (!done.await(30, TimeUnit.SECONDS)) {
            log.warn("Timeout waiting for all responses. Received: {}", received.get());
        }
        final long totalNs = System.nanoTime() - startNs;

        wsClient.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").join();
        printResults(histogram, totalNs, MEASURE_COUNT);
    }

    private static void sendOrder(final HttpClient client, final String url,
                                   final String token, final String body) throws Exception {
        final HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        client.send(req, HttpResponse.BodyHandlers.discarding());
    }

    private static String buildOrderJson(final int symbolId) {
        return "{\"symbolId\":" + symbolId
            + ",\"side\":\"BUY\""
            + ",\"type\":\"LIMIT\""
            + ",\"price\":\"50000.00\""
            + ",\"quantity\":\"0.01\""
            + ",\"timeInForce\":\"GTC\""
            + ",\"leverage\":1}";
    }

    private static void printResults(final Histogram h, final long totalNs,
                                      final int count) {
        log.info("=== End-to-End Latency Results ===");
        log.info("Samples      : {}", h.getTotalCount());
        log.info("Total time   : {:.2f} ms", totalNs / 1e6);
        log.info("Throughput   : {:.0f} orders/sec", count / (totalNs / 1e9));
        log.info("Min          : {} μs", h.getMinValue() / 1000);
        log.info("P50          : {} μs", h.getValueAtPercentile(50) / 1000);
        log.info("P90          : {} μs", h.getValueAtPercentile(90) / 1000);
        log.info("P95          : {} μs", h.getValueAtPercentile(95) / 1000);
        log.info("P99          : {} μs", h.getValueAtPercentile(99) / 1000);
        log.info("P99.9        : {} μs", h.getValueAtPercentile(99.9) / 1000);
        log.info("Max          : {} μs", h.getMaxValue() / 1000);

        final long p99Us = h.getValueAtPercentile(99) / 1000;
        if (p99Us < 1_000) {
            log.info("PASS: P99 = {} μs < 1000 μs (1 ms). Phase 4 target achieved!", p99Us);
        } else {
            log.warn("WARN: P99 = {} μs >= 1 ms. Check network/GC/CPU affinity.", p99Us);
        }
    }
}
```

### 2.3 期望测试结果

**同机部署（localhost，Linux 物理机，隔离 CPU 核心）：**

```
=== End-to-End Latency Results ===
Samples      : 10000
Total time   : 12345.67 ms
Throughput   : 810 orders/sec   ← 受限于 1ms sleep，实际并发可达 10K+
Min          : 112 μs
P50          : 245 μs
P90          : 412 μs
P95          : 567 μs
P99          : 874 μs           ← < 1ms ✓
P99.9        : 1,234 μs
Max          : 8,192 μs
PASS: P99 = 874 μs < 1000 μs (1 ms). Phase 4 target achieved!
```

**macOS 开发机（参考值）：**

```
P99 : 3,500 ~ 8,000 μs    ← macOS 内核调度+无真实共享内存，属正常范围
```

---

## 3. Gateway 吞吐量基准

文件：`benchmark/src/main/java/com/trading/benchmark/GatewayThroughputBenchmark.java`

```java
package com.trading.benchmark;

import com.trading.gateway.auth.JwtAuthenticator;
import com.trading.gateway.ratelimit.TokenBucketRateLimiter;
import com.trading.gateway.netty.HttpServerHandler;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * Gateway 关键路径微基准：JWT 验签 + 限流 + JSON 解析 + SBE 编码。
 *
 * <p>验证目标：单次请求处理 < 5μs（不含网络 IO）。
 *
 * @author Reln Ding
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class GatewayThroughputBenchmark {

    private JwtAuthenticator      jwtAuth;
    private TokenBucketRateLimiter rateLimiter;
    private String                 validToken;
    private String                 orderJson;

    @Setup
    public void setup() {
        jwtAuth     = new JwtAuthenticator("dev-secret-key-32-bytes-minimum!!!");
        rateLimiter = new TokenBucketRateLimiter(10_000, 10_000, 1L);  // 不限流
        validToken  = jwtAuth.generate(1001L, 3600L);
        orderJson   = "{\"symbolId\":1,\"side\":\"BUY\",\"type\":\"LIMIT\","
                    + "\"price\":\"50000.00\",\"quantity\":\"0.1\","
                    + "\"timeInForce\":\"GTC\",\"leverage\":1}";
    }

    /** JWT 验签 */
    @Benchmark
    public boolean jwtVerify() {
        return jwtAuth.authenticate(validToken).passed;
    }

    /** 令牌桶限流检查 */
    @Benchmark
    public boolean rateLimitCheck() {
        return rateLimiter.tryAcquire(1001L);
    }

    /** JSON 价格解析 → long */
    @Benchmark
    public long parsePriceToLong() {
        return HttpServerHandler.parsePriceToLong("50000.25", 2);
    }

    /** 完整 JSON 字段解析 */
    @Benchmark
    public String parseStrField() {
        return HttpServerHandler.parseStrField(orderJson, "side");
    }

    public static void main(final String[] args) throws Exception {
        new Runner(new OptionsBuilder()
            .include(GatewayThroughputBenchmark.class.getSimpleName())
            .build()).run();
    }
}
```

**期望结果（参考值）：**

```
Benchmark                                  Mode  Cnt  Score   Error  Units
GatewayThroughputBenchmark.jwtVerify       avgt    5  1.234 ± 0.04  μs/op
GatewayThroughputBenchmark.rateLimitCheck  avgt    5  0.089 ± 0.01  μs/op
GatewayThroughputBenchmark.parsePriceToLong avgt   5  0.023 ± 0.00  μs/op
GatewayThroughputBenchmark.parseStrField   avgt    5  0.156 ± 0.01  μs/op
```

---

## 4. Journal 写入吞吐量基准

文件：`benchmark/src/main/java/com/trading/benchmark/JournalWriterBenchmark.java`

```java
package com.trading.benchmark;

import com.trading.journal.writer.JournalEvent;
import com.trading.journal.writer.MappedFileJournalWriter;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Journal 写入吞吐量基准测试。
 *
 * <p>验证目标：> 500K events/sec（不含 fsync）。
 *
 * @author Reln Ding
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class JournalWriterBenchmark {

    private MappedFileJournalWriter writer;
    private UnsafeBuffer            payload;
    private long                    seqNo;
    private File                    tempFile;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        tempFile = File.createTempFile("journal-bench-", ".log");
        tempFile.deleteOnExit();
        writer  = new MappedFileJournalWriter(MappedFileJournalWriter.DEFAULT_MAPPED_SIZE);
        writer.open(tempFile);
        payload = new UnsafeBuffer(ByteBuffer.allocateDirect(68));  // MatchResult 典型大小
        // 填充模拟载荷
        for (int i = 0; i < 68; i++) payload.putByte(i, (byte) i);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        writer.close();
    }

    @Benchmark
    public int writeEvent() {
        final int result = writer.write(++seqNo, System.nanoTime(),
                                        JournalEvent.TYPE_TRADE,
                                        payload, 0, 68);
        if (result < 0) {
            // 模拟轮转（重置写位置，实际不创建新文件）
            seqNo = 0;
            try { writer.open(tempFile); } catch (Exception ignored) {}
        }
        return result;
    }

    public static void main(final String[] args) throws Exception {
        new Runner(new OptionsBuilder()
            .include(JournalWriterBenchmark.class.getSimpleName())
            .build()).run();
    }
}
```

**期望结果（参考值）：**

```
Benchmark                        Mode  Cnt        Score   Units
JournalWriterBenchmark.writeEvent thrpt   5  8,234,567  ops/s   ← 远超 500K 目标
```

> **注：** MappedFile 写入本质是内存写，fsync 批量执行（每 10ms 或 4096 条），
> 吞吐量远高于传统 FileOutputStream。

### 4.1 运行基准测试

```bash
cd trading-platform
mvn package -pl benchmark -am -q -Dcheckstyle.skip=true

# Gateway 微基准
java --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     -jar benchmark/target/benchmarks.jar "GatewayThroughputBenchmark" \
     -rf json -rff results-gateway.json

# Journal 写入基准
java -Xms1g -Xmx1g \
     -jar benchmark/target/benchmarks.jar "JournalWriterBenchmark" \
     -rf json -rff results-journal.json
```

---

## 5. Phase 4 完整验收检查清单

### 5.1 Gateway Service

#### Part 1 基础设施

- [ ] `JwtAuthenticator`：HS256 验签正确；constantTimeEquals 防时序攻击；过期/格式/null 全覆盖
- [ ] `HmacAuthenticator`：时间窗口 ±5000ms；revoke 即时生效；null apiKey 返回 MISSING
- [ ] `TokenBucketRateLimiter`：惰性补充不超容量；不同账户独立；remove 后重建满桶
- [ ] `AeronClusterClient`：sendNewOrder/sendCancelOrder SBE 字段编码完整；offer 返回值有处理
- [ ] `GatewayEgressListener`：ExecutionReport 解码后路由到正确账户 Channel

#### Part 2 HTTP + WebSocket Handler

- [ ] `HttpServerHandler.parsePriceToLong`：小数/整数/补零/截断 四种场景通过
- [ ] `HttpServerHandler`：POST/DELETE/GET 路由正确；鉴权失败 401；限流失败 429
- [ ] `WebSocketHandler`：auth/subscribe/unsubscribe/ping/unknown 五个 action 路由正确
- [ ] `WebSocketHandler.channelInactive`：从 sessionRegistry + 订阅表全部移除
- [ ] `SessionRegistry`：多连接同账户；unregister 后完全清理；不存在 Channel 不抛异常

#### Part 3 Push Service

- [ ] `DepthBook`：买盘降序、卖盘升序；qty=0 移除档位；Top-N JSON 格式正确
- [ ] `DepthDispatcher`：差量 JSON 含 e/s/seq/b/a；定时 1s 全量快照分支覆盖
- [ ] `TradeDispatcher`：成交 JSON 含 e/s/seq/p/q/side/t；无订阅者不报错
- [ ] `TickerAggregator`：首次成交四价相同；涨跌幅正确（含上涨/下跌/零变动）；无数据返回 null
- [ ] `MarketDataSubscriber`：MatchResult 路由到 Trade+Ticker；OrderBookUpdate 路由到 Depth

#### Part 4 Journal Service

- [ ] `MappedFileJournalWriter`：帧格式字段偏移正确；空间不足返回 -1；rotate 重置位置
- [ ] `JournalReplayService`：fromSeqNo 过滤；CRC 失败帧跳过；空目录返回 0
- [ ] `JournalRotationManager`：按日期创建子目录；文件名含 seqNo；needsRotation 触发正确
- [ ] `JournalEventSubscriber`：批量 fsync 策略（4096 条或 10ms）；空间不足触发轮转

### 5.2 测试数量验收

| Part           | 测试文件                                               | 测试数                            |
|----------------|----------------------------------------------------|--------------------------------|
| Part 1         | `AuthenticatorTest` + `TokenBucketRateLimiterTest` | 22                             |
| Part 2         | `HandlerTest`                                      | 18                             |
| Part 3         | `PushDispatcherTest`                               | 18                             |
| Part 4         | `JournalWriterTest`                                | 14                             |
| **Phase 4 合计** |                                                    | **72 个测试，0 Failures，0 Errors** |

### 5.3 性能验收

- [ ] JWT 验签单次 < 2μs（JMH）
- [ ] 令牌桶限流检查单次 < 0.1μs（JMH）
- [ ] Journal 写入吞吐 > 500K events/sec（JMH）
- [ ] 端到端延迟 P99 < 1ms（Linux 物理机，同机部署）
- [ ] 稳定运行 30 分钟无 OOM，无 Full GC

### 5.4 代码质量

- [ ] 所有新增类 `@author` 为 `Reln Ding`
- [ ] JWT `constantTimeEquals` 防时序攻击（不使用 `String.equals`）
- [ ] Netty Handler 中无阻塞操作（无 `Thread.sleep`、无同步 DB 调用）
- [ ] Push Service 广播使用 `retainedDuplicate()` + 最后 `release()`，无内存泄漏
- [ ] Journal CRC32 每帧写入，回放时校验，损坏帧跳过不中断

---

## 6. Phase 1-4 项目总验收

### 6.1 全项目测试总计

```bash
# 一键运行所有模块
cd trading-platform
mvn test -Dcheckstyle.skip=true
```

| 模块                | 测试数                             |
|-------------------|---------------------------------|
| `common-util`     | 5                               |
| `matching-engine` | 69                              |
| `counter-service` | 107                             |
| `gateway-service` | 72                              |
| `push-service`    | 18                              |
| `journal-service` | 14                              |
| **总计**            | **285 个测试，0 Failures，0 Errors** |

### 6.2 各阶段性能目标回顾

| 阶段      | 验证目标                                 | 状态  |
|---------|--------------------------------------|-----|
| Phase 1 | Aeron IPC + Disruptor 单链路 P99 < 1μs  | [ ] |
| Phase 2 | 撮合引擎单交易对 > 500K orders/sec，P99 < 5μs | [ ] |
| Phase 3 | 完整下单→撮合→回报链路 P99 < 10μs              | [ ] |
| Phase 4 | 端到端客户端→交易所→客户端 P99 < 1ms             | [ ] |

### 6.3 全项目架构验收

```
┌────────────────────────────────────────────────────────────┐
│  Client（REST / WebSocket）                                  │
│  POST /api/v1/order  ←→  ws://gateway/ws                   │
└────────────────────┬──────────────────────────────────────-┘
                     │ HTTPS / WSS
┌────────────────────▼───────────────────────────────────────┐
│  Gateway Service（Netty）                            Phase 4 │
│  • JWT / HMAC 鉴权  • 令牌桶限流  • JSON→SBE 编码           │
│  • AeronClusterClient → Cluster Ingress                     │
│  • GatewayEgressListener → WebSocket 回报推送               │
└────────────────────┬──────────────────────────────────────-┘
                     │ Aeron UDP
┌────────────────────▼───────────────────────────────────────┐
│  Aeron Cluster（3节点 Raft）                         Phase 3 │
│  ┌────────────────────────────────────────────────────┐    │
│  │  Counter Service（ClusteredService 确定性状态机）    │    │
│  │  • 账户余额管理（AccountManager）                   │    │
│  │  • 保证金/仓位管理（PositionManager）               │    │
│  │  • 风控（Balance+Position+PriceBand）               │    │
│  │  • 5段 Disruptor Pipeline                          │    │
│  │  • ExecutionReportProcessor                        │    │
│  └─────────────────┬──────────────────────────────────┘    │
│                    │ Aeron IPC stream=2                     │
│  ┌─────────────────▼──────────────────────────────────┐    │
│  │  Matching Engine（每交易对独立线程）          Phase 2 │    │
│  │  • OrderBook（LongTreeMap + 双向链表 + 对象池）      │    │
│  │  • OrderMatcher（Limit/Market/IOC/FOK/PostOnly）    │    │
│  │  • 3段 Disruptor Pipeline                          │    │
│  └────────┬──────────────────┬──────────────────────--┘    │
│           │ stream=3         │ stream=4        │ stream=5   │
└───────────┼──────────────────┼─────────────────┼───────────┘
            │                  │                 │
   ┌────────▼────┐   ┌─────────▼──────┐  ┌──────▼──────┐
   │ Counter     │   │  Push Service  │  │   Journal   │
   │(回报处理)   │   │  深度/成交/Tick │  │   Service   │
   │ Phase 3     │   │  WS广播 Phase 4│  │  Phase 4    │
   └─────────────┘   └────────────────┘  └─────────────┘
```

### 6.4 技术栈验收清单

- [ ] **Java 21**：全项目统一 Java 21，使用 Text Blocks / Records / Switch Expressions
- [ ] **Aeron 1.44.1**：IPC + UDP + Cluster 全部用到；Media Driver 配置正确
- [ ] **LMAX Disruptor 4.0.0**：撮合引擎 3段 Pipeline + 柜台 5段 Pipeline
- [ ] **SBE 1.30.0**：所有跨进程消息使用 SBE 编解码，零对象分配
- [ ] **Agrona 1.21.2**：Long2ObjectHashMap / Int2ObjectHashMap 替代 JDK HashMap
- [ ] **Netty 4.1.x**：Gateway HTTP/WS 服务器，零拷贝 DirectBuffer
- [ ] **ZGC**：生产环境 JVM 配置 `-XX:+UseZGC`，停顿 < 1ms
- [ ] **HdrHistogram**：延迟分布测量，P50/P99/P99.9 三档

### 6.5 运维就绪清单

- [ ] 各服务均有 `addShutdownHook`，优雅关闭（先停接收，再刷盘，最后关连接）
- [ ] 关键路径有 SLF4J 日志，级别分层（热路径用 DEBUG，避免生产日志影响延迟）
- [ ] Aeron Media Driver 使用 `/dev/shm`（Linux 生产），`/tmp`（macOS 开发）
- [ ] Journal 轮转文件名含 seqNo，便于故障恢复时定位起始点
- [ ] 快照策略：每 5 分钟或 N 万条日志触发 Aeron Cluster 快照

### 6.6 下一步建议（Phase 5+）

阶段性验收通过后，可继续推进以下方向：

| 优先级 | 方向                  | 说明                                                  |
|-----|---------------------|-----------------------------------------------------|
| 高   | Aeron Cluster 三节点部署 | 实际部署 Raft 三节点，验证 Leader 切换 < 3s                     |
| 高   | 压力测试（目标 1M TPS）     | JMeter / 自研压测工具，多账户多交易对并发                           |
| 中   | 监控接入                | Prometheus 指标暴露（Aeron counters + Disruptor metrics） |
| 中   | REST 查询接口实现         | 查询订单/余额从 Counter Service 同步查询                       |
| 低   | GTD 订单过期定时器         | Aeron Cluster `registerTimer` + `onTimerEvent`      |
| 低   | 强平引擎                | 监控 `isLiquidatable()`，发送系统强平订单                      |

---

## 附录：完整文档索引

| 文件                                                  | 内容                                                     |
|-----------------------------------------------------|--------------------------------------------------------|
| `high-performance-trading-platform-design.md`       | 系统整体设计方案                                               |
| `phase1-setup-guide.md`                             | Phase 1：基础框架（Maven / SBE / Aeron IPC / Disruptor Demo） |
| `phase2-matching-engine-part1-orderbook.md`         | Phase 2 Part 1：OrderBook 数据结构                          |
| `phase2-matching-engine-part2-matcher.md`           | Phase 2 Part 2：撮合算法                                    |
| `phase2-matching-engine-part3-pipeline.md`          | Phase 2 Part 3：Disruptor Pipeline + Aeron IPC 集成       |
| `phase2-matching-engine-part4-tests.md`             | Phase 2 Part 4：全场景单元测试                                 |
| `phase2-matching-engine-part5-benchmark.md`         | Phase 2 Part 5：性能基准 + 验收清单                             |
| `phase3-counter-service-part1-domain-model.md`      | Phase 3 Part 1：领域模型                                    |
| `phase3-counter-service-part2-account-position.md`  | Phase 3 Part 2：AccountManager + PositionManager        |
| `phase3-counter-service-part3-risk-fee.md`          | Phase 3 Part 3：风控模块 + FeeCalculator                    |
| `phase3-counter-service-part4-pipeline.md`          | Phase 3 Part 4：柜台 Disruptor Pipeline                   |
| `phase3-counter-service-part5-cluster-checklist.md` | Phase 3 Part 5：ClusteredService + 验收清单                 |
| `phase4-gateway-push-part1-gateway-infra.md`        | Phase 4 Part 1：Gateway 基础设施                            |
| `phase4-gateway-push-part2-gateway-handlers.md`     | Phase 4 Part 2：Gateway HTTP + WebSocket                |
| `phase4-gateway-push-part3-push-service.md`         | Phase 4 Part 3：Push Service                            |
| `phase4-gateway-push-part4-journal.md`              | Phase 4 Part 4：Journal Service                         |
| `phase4-gateway-push-part5-checklist.md`            | Phase 4 Part 5：验收清单（本文）                                |
