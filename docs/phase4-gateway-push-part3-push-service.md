# Phase 4 Gateway 与推送服务 — Part 3：Push Service

> **目标：** 实现 Push Service，订阅撮合引擎行情事件，聚合深度/成交/Ticker 数据，
> 通过 WebSocket 广播给订阅客户端。
>
> **前置条件：** Part 2 完成，Gateway 40 个测试通过  
> **本节验证目标：** 深度差量计算正确，Ticker 24h 滚动统计正确，单元测试通过

---

## 目录

1. [Push Service 架构](#1-push-service-架构)
2. [push-service POM](#2-push-service-pom)
3. [DepthBook（本地订单簿镜像）](#3-depthbook本地订单簿镜像)
4. [DepthDispatcher（深度推送）](#4-depthdispatcher深度推送)
5. [TradeDispatcher（成交推送）](#5-tradedispatcher成交推送)
6. [TickerAggregator（Ticker 聚合）](#6-tickeraggregator-ticker-聚合)
7. [SubscriptionRegistry（订阅注册表）](#7-subscriptionregistry订阅注册表)
8. [MarketDataSubscriber（Aeron 订阅）](#8-marketdatasubscriberaeron-订阅)
9. [PushServiceMain（启动入口）](#9-pushservicemain启动入口)
10. [Push Service 单元测试](#10-push-service-单元测试)

---

## 1. Push Service 架构

```
撮合引擎 Aeron IPC stream=4
          │
          ▼
┌─────────────────────────────────────────────────────────┐
│  MarketDataSubscriber (BusySpin 轮询)                    │
│  • 解码 SBE OrderBookUpdate (templateId=302)             │
│  • 解码 SBE MatchResult (templateId=301)                 │
└──────────┬──────────────────────┬────────────────────────┘
           │                      │
    OrderBookUpdate            MatchResult
           │                      │
    ┌──────▼──────┐        ┌──────▼──────┐
    │DepthDispatcher│      │TradeDispatcher│
    │• 更新本地    │        │• 逐笔成交 JSON│
    │  DepthBook  │        └──────┬───────┘
    │• 计算差量   │               │
    │• 定时全量   │        ┌──────▼──────┐
    └──────┬──────┘        │TickerAggregator│
           │               │• 最新价      │
           │               │• 24h 统计   │
           └──────┬─────────┘
                  │
    ┌─────────────▼──────────────────────┐
    │  WebSocketPushHandler               │
    │  • 按 symbolId 过滤订阅者            │
    │  • Netty writeAndFlush              │
    └────────────────────────────────────┘
```

---

## 2. push-service POM

文件：`push-service/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.trading</groupId>
        <artifactId>trading-platform</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>push-service</artifactId>
    <name>Push Service</name>

    <dependencies>
        <dependency>
            <groupId>com.trading</groupId>
            <artifactId>common-sbe</artifactId>
        </dependency>
        <dependency>
            <groupId>io.aeron</groupId>
            <artifactId>aeron-all</artifactId>
        </dependency>
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-all</artifactId>
        </dependency>
        <dependency>
            <groupId>org.agrona</groupId>
            <artifactId>agrona</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

---

## 3. DepthBook（本地订单簿镜像）

Push Service 在本地维护一份每个交易对的深度快照，用于计算差量更新。

文件：`push-service/src/main/java/com/trading/push/dispatcher/DepthBook.java`

```java
package com.trading.push.dispatcher;

import java.util.TreeMap;

/**
 * 本地订单簿深度镜像（Push Service 侧维护，用于计算差量）。
 *
 * <p>数据来源：接收撮合引擎的 {@code OrderBookUpdate} 事件（每次成交后的变化档位）。
 * Push Service 不是权威数据源，仅用于行情推送，不影响撮合正确性。
 *
 * @author Reln Ding
 */
public final class DepthBook {

    public final int symbolId;

    /** 买盘：价格降序，Key=价格（固定精度 long），Value=该档总量（0 表示档位清空）*/
    private final TreeMap<Long, Long> bids = new TreeMap<>(java.util.Comparator.reverseOrder());

    /** 卖盘：价格升序 */
    private final TreeMap<Long, Long> asks = new TreeMap<>();

    /** 最新成交序列号（用于检测消息丢失）*/
    private long lastSequenceNo = 0L;

    public DepthBook(final int symbolId) {
        this.symbolId = symbolId;
    }

    /**
     * 应用单档位变更（来自 OrderBookUpdate 事件）。
     *
     * @param side      方向（1=Buy 买盘，2=Sell 卖盘）
     * @param price     价格
     * @param newQty    新的总量（0 = 该档已清空，需删除）
     * @param seqNo     撮合序列号
     */
    public void applyUpdate(final byte side, final long price,
                            final long newQty, final long seqNo) {
        lastSequenceNo = seqNo;
        final TreeMap<Long, Long> book = (side == 1) ? bids : asks;
        if (newQty <= 0) {
            book.remove(price);
        } else {
            book.put(price, newQty);
        }
    }

    /**
     * 获取买盘 Top-N 档（用于全量快照推送）。
     *
     * @param levels 档数（如 5/10/20）
     * @return 价格-数量对的 JSON 数组字符串
     */
    public String getBidsJson(final int levels) {
        return toJson(bids, levels);
    }

    /**
     * 获取卖盘 Top-N 档。
     */
    public String getAsksJson(final int levels) {
        return toJson(asks, levels);
    }

    public long getLastSequenceNo() { return lastSequenceNo; }

    public boolean isEmpty() {
        return bids.isEmpty() && asks.isEmpty();
    }

    private static String toJson(final TreeMap<Long, Long> book, final int levels) {
        final StringBuilder sb = new StringBuilder("[");
        int count = 0;
        for (final java.util.Map.Entry<Long, Long> e : book.entrySet()) {
            if (count > 0) sb.append(",");
            sb.append("[").append(e.getKey()).append(",").append(e.getValue()).append("]");
            if (++count >= levels) break;
        }
        sb.append("]");
        return sb.toString();
    }
}
```

---

## 4. DepthDispatcher（深度推送）

文件：`push-service/src/main/java/com/trading/push/dispatcher/DepthDispatcher.java`

```java
package com.trading.push.dispatcher;

import com.trading.push.subscription.SubscriptionRegistry;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.agrona.collections.Int2ObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 订单簿深度推送器。
 *
 * <p>推送策略：
 * <ul>
 *   <li><b>差量更新</b>：每次收到 OrderBookUpdate 即推送变化的价格档位，数据量小，延迟低。</li>
 *   <li><b>定时全量</b>：每 1 秒推送一次 Top-N 档完整快照，确保客户端状态一致。</li>
 * </ul>
 *
 * <p>推送消息格式（差量）：
 * <pre>
 * {"e":"depthUpdate","s":1,"seq":100,"b":[[5000025,100]],"a":[]}
 * </pre>
 *
 * <p>推送消息格式（全量）：
 * <pre>
 * {"e":"depth","s":1,"seq":100,"b":[[5001,200],[5000,100]],"a":[[5002,150]]}
 * </pre>
 *
 * @author Reln Ding
 */
public final class DepthDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DepthDispatcher.class);

    /** symbolId → DepthBook */
    private final Int2ObjectHashMap<DepthBook> depthBooks =
        new Int2ObjectHashMap<>(64, 0.6f);

    private final SubscriptionRegistry subscriptionRegistry;

    /** 全量快照推送间隔（纳秒）*/
    private static final long SNAPSHOT_INTERVAL_NS = 1_000_000_000L;  // 1 秒

    /** symbolId → 上次全量推送时间（纳秒）*/
    private final Int2ObjectHashMap<Long> lastSnapshotNs =
        new Int2ObjectHashMap<>(64, 0.6f);

    public DepthDispatcher(final SubscriptionRegistry subscriptionRegistry) {
        this.subscriptionRegistry = subscriptionRegistry;
    }

    /**
     * 处理 OrderBookUpdate 事件（撮合引擎每笔成交后产生）。
     *
     * @param symbolId 交易对 ID
     * @param side     方向（1=买，2=卖）
     * @param price    变化档位价格
     * @param newQty   新的总量（0=清空）
     * @param seqNo    撮合序列号
     * @param nowNs    当前时间（纳秒）
     */
    public void onOrderBookUpdate(final int symbolId, final byte side,
                                  final long price, final long newQty,
                                  final long seqNo, final long nowNs) {
        DepthBook book = depthBooks.get(symbolId);
        if (book == null) {
            book = new DepthBook(symbolId);
            depthBooks.put(symbolId, book);
        }
        book.applyUpdate(side, price, newQty, seqNo);

        // 推送差量更新
        final String deltaJson = buildDeltaJson(symbolId, side, price, newQty, seqNo);
        broadcast(symbolId, deltaJson);

        // 定时全量快照
        final long last = lastSnapshotNs.getOrDefault(symbolId, 0L);
        if (nowNs - last >= SNAPSHOT_INTERVAL_NS) {
            broadcastSnapshot(symbolId, book, seqNo);
            lastSnapshotNs.put(symbolId, nowNs);
        }
    }

    private void broadcastSnapshot(final int symbolId, final DepthBook book, final long seqNo) {
        final String json = "{\"e\":\"depth\",\"s\":" + symbolId
            + ",\"seq\":" + seqNo
            + ",\"b\":" + book.getBidsJson(20)
            + ",\"a\":" + book.getAsksJson(20)
            + "}";
        broadcast(symbolId, json);
    }

    private void broadcast(final int symbolId, final String json) {
        final Set<Channel> channels = subscriptionRegistry.getDepthSubscribers(symbolId);
        if (channels == null || channels.isEmpty()) return;
        final TextWebSocketFrame frame = new TextWebSocketFrame(json);
        for (final Channel ch : channels) {
            if (ch.isActive()) {
                ch.writeAndFlush(frame.retainedDuplicate());
            }
        }
        frame.release();
    }

    private static String buildDeltaJson(final int symbolId, final byte side,
                                          final long price, final long newQty,
                                          final long seqNo) {
        final String sideKey = (side == 1) ? "b" : "a";
        return "{\"e\":\"depthUpdate\",\"s\":" + symbolId
            + ",\"seq\":" + seqNo
            + ",\"" + sideKey + "\":[[" + price + "," + newQty + "]]"
            + ",\"" + (side == 1 ? "a" : "b") + "\":[]}";
    }
}
```

---

## 5. TradeDispatcher（成交推送）

文件：`push-service/src/main/java/com/trading/push/dispatcher/TradeDispatcher.java`

```java
package com.trading.push.dispatcher;

import com.trading.push.subscription.SubscriptionRegistry;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 逐笔成交事件推送器。
 *
 * <p>推送消息格式：
 * <pre>
 * {"e":"trade","s":1,"seq":100,"p":5000025,"q":100000,"side":2,"t":1700000000000}
 * </pre>
 * 其中 side = makerSide（1=买方挂单被吃，即成交方向为卖；2=反之）
 *
 * @author Reln Ding
 */
public final class TradeDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TradeDispatcher.class);

    private final SubscriptionRegistry subscriptionRegistry;

    public TradeDispatcher(final SubscriptionRegistry subscriptionRegistry) {
        this.subscriptionRegistry = subscriptionRegistry;
    }

    /**
     * 处理一笔成交事件。
     *
     * @param symbolId    交易对 ID
     * @param seqNo       撮合序列号
     * @param price       成交价
     * @param quantity    成交量
     * @param makerSide   Maker 方向（1=买单挂单，2=卖单挂单）
     * @param timestampNs 成交时间（纳秒）
     */
    public void onTrade(final int symbolId, final long seqNo,
                        final long price, final long quantity,
                        final byte makerSide, final long timestampNs) {
        final String json = "{\"e\":\"trade\",\"s\":" + symbolId
            + ",\"seq\":" + seqNo
            + ",\"p\":" + price
            + ",\"q\":" + quantity
            + ",\"side\":" + makerSide
            + ",\"t\":" + (timestampNs / 1_000_000L)  // 转为毫秒
            + "}";

        final Set<Channel> channels = subscriptionRegistry.getTradeSubscribers(symbolId);
        if (channels == null || channels.isEmpty()) return;

        final TextWebSocketFrame frame = new TextWebSocketFrame(json);
        for (final Channel ch : channels) {
            if (ch.isActive()) {
                ch.writeAndFlush(frame.retainedDuplicate());
            }
        }
        frame.release();
    }
}
```

---

## 6. TickerAggregator（Ticker 聚合）

文件：`push-service/src/main/java/com/trading/push/dispatcher/TickerAggregator.java`

```java
package com.trading.push.dispatcher;

import com.trading.push.subscription.SubscriptionRegistry;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.agrona.collections.Int2ObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Ticker 聚合器：维护每个交易对的 24h 滚动统计。
 *
 * <p>统计字段：
 * <ul>
 *   <li>最新成交价（lastPrice）</li>
 *   <li>24h 开盘价（openPrice）：24h 前第一笔成交价，每笔成交后检查是否需滚动</li>
 *   <li>24h 最高价（highPrice）</li>
 *   <li>24h 最低价（lowPrice）</li>
 *   <li>24h 成交量（volume）</li>
 *   <li>24h 成交额（turnover）</li>
 *   <li>24h 涨跌幅（priceChangePercent）= (lastPrice - openPrice) / openPrice × 100</li>
 * </ul>
 *
 * <p>简化实现：不维护真正的滑动窗口，用全局累计量近似（生产环境需 Redis ZSET 实现精确 24h 窗口）。
 *
 * <p>推送消息格式：
 * <pre>
 * {"e":"ticker","s":1,"c":5000025,"o":4900000,"h":5100000,"l":4800000,
 *  "v":1000000,"q":5000000000,"P":"2.04","t":1700000000000}
 * </pre>
 *
 * @author Reln Ding
 */
public final class TickerAggregator {

    private static final Logger log = LoggerFactory.getLogger(TickerAggregator.class);

    private final SubscriptionRegistry subscriptionRegistry;

    /** symbolId → TickerState */
    private final Int2ObjectHashMap<TickerState> tickers = new Int2ObjectHashMap<>(64, 0.6f);

    public TickerAggregator(final SubscriptionRegistry subscriptionRegistry) {
        this.subscriptionRegistry = subscriptionRegistry;
    }

    /**
     * 处理新成交事件，更新并推送 Ticker。
     */
    public void onTrade(final int symbolId, final long price,
                        final long quantity, final long turnover,
                        final long timestampNs) {
        TickerState ticker = tickers.get(symbolId);
        if (ticker == null) {
            ticker = new TickerState(price);
            tickers.put(symbolId, ticker);
        }
        ticker.update(price, quantity, turnover);

        final String json = buildTickerJson(symbolId, ticker, timestampNs);
        final Set<Channel> channels = subscriptionRegistry.getTickerSubscribers(symbolId);
        if (channels == null || channels.isEmpty()) return;

        final TextWebSocketFrame frame = new TextWebSocketFrame(json);
        for (final Channel ch : channels) {
            if (ch.isActive()) {
                ch.writeAndFlush(frame.retainedDuplicate());
            }
        }
        frame.release();
    }

    /**
     * 获取当前 Ticker 状态（用于 REST 查询）。
     *
     * @return JSON 字符串，或 null（无数据）
     */
    public String getTickerJson(final int symbolId) {
        final TickerState t = tickers.get(symbolId);
        return t == null ? null : buildTickerJson(symbolId, t, System.nanoTime());
    }

    private static String buildTickerJson(final int symbolId, final TickerState t,
                                           final long timestampNs) {
        // 涨跌幅百分比（保留2位小数）
        final String pctStr;
        if (t.openPrice > 0) {
            final long pctMicros = (t.lastPrice - t.openPrice) * 1_000_000L / t.openPrice;
            pctStr = String.format("%.2f", pctMicros / 10_000.0);
        } else {
            pctStr = "0.00";
        }
        return "{\"e\":\"ticker\",\"s\":" + symbolId
            + ",\"c\":" + t.lastPrice
            + ",\"o\":" + t.openPrice
            + ",\"h\":" + t.highPrice
            + ",\"l\":" + t.lowPrice
            + ",\"v\":" + t.volume
            + ",\"q\":" + t.turnover
            + ",\"P\":\"" + pctStr + "\""
            + ",\"t\":" + (timestampNs / 1_000_000L)
            + "}";
    }

    // ----------------------------------------------------------------
    // 内部状态
    // ----------------------------------------------------------------

    static final class TickerState {

        long openPrice;
        long lastPrice;
        long highPrice;
        long lowPrice;
        long volume;    // 成交量（固定精度）
        long turnover;  // 成交额（固定精度）

        TickerState(final long initPrice) {
            this.openPrice = initPrice;
            this.lastPrice = initPrice;
            this.highPrice = initPrice;
            this.lowPrice  = initPrice;
        }

        void update(final long price, final long qty, final long trnover) {
            lastPrice  = price;
            highPrice  = Math.max(highPrice, price);
            lowPrice   = (lowPrice == 0) ? price : Math.min(lowPrice, price);
            volume    += qty;
            turnover  += trnover;
        }
    }
}
```

---

## 7. SubscriptionRegistry（订阅注册表）

Push Service 侧的订阅管理，与 Gateway 侧的 `WebSocketHandler` 中的 Map 对应，
在生产部署中可通过共享内存或 Aeron IPC 同步；Phase 4 直接注入 `WebSocketHandler` 的引用。

文件：`push-service/src/main/java/com/trading/push/subscription/SubscriptionRegistry.java`

```java
package com.trading.push.subscription;

import io.netty.channel.Channel;

import java.util.Set;

/**
 * 订阅注册表接口。
 *
 * <p>解耦 Push Service 与 Gateway 之间的依赖，便于测试和替换实现。
 *
 * @author Reln Ding
 */
public interface SubscriptionRegistry {

    /** 获取订阅了指定交易对深度行情的 Channel 集合 */
    Set<Channel> getDepthSubscribers(int symbolId);

    /** 获取订阅了指定交易对逐笔成交的 Channel 集合 */
    Set<Channel> getTradeSubscribers(int symbolId);

    /** 获取订阅了指定交易对 Ticker 的 Channel 集合 */
    Set<Channel> getTickerSubscribers(int symbolId);
}
```

---

## 8. MarketDataSubscriber（Aeron 订阅）

文件：`push-service/src/main/java/com/trading/push/aeron/MarketDataSubscriber.java`

```java
package com.trading.push.aeron;

import com.trading.push.dispatcher.DepthDispatcher;
import com.trading.push.dispatcher.TickerAggregator;
import com.trading.push.dispatcher.TradeDispatcher;
import com.trading.sbe.*;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 撮合引擎行情事件订阅器（Aeron IPC stream=4）。
 *
 * <p>支持消息类型：
 * <ul>
 *   <li>{@code MatchResult}（templateId=301）→ TradeDispatcher + TickerAggregator</li>
 *   <li>{@code OrderBookUpdate}（templateId=302）→ DepthDispatcher</li>
 * </ul>
 *
 * @author Reln Ding
 */
public final class MarketDataSubscriber implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MarketDataSubscriber.class);
    private static final int FRAGMENT_LIMIT = 10;

    private final Subscription      subscription;
    private final DepthDispatcher   depthDispatcher;
    private final TradeDispatcher   tradeDispatcher;
    private final TickerAggregator  tickerAggregator;

    private final MessageHeaderDecoder   headerDecoder  = new MessageHeaderDecoder();
    private final MatchResultDecoder     matchDecoder   = new MatchResultDecoder();
    private final OrderBookUpdateDecoder obUpdateDecoder = new OrderBookUpdateDecoder();

    private final IdleStrategy idleStrategy = new BusySpinIdleStrategy();
    private volatile boolean   running      = true;

    public MarketDataSubscriber(final Subscription subscription,
                                final DepthDispatcher depthDispatcher,
                                final TradeDispatcher tradeDispatcher,
                                final TickerAggregator tickerAggregator) {
        this.subscription     = subscription;
        this.depthDispatcher  = depthDispatcher;
        this.tradeDispatcher  = tradeDispatcher;
        this.tickerAggregator = tickerAggregator;
    }

    @Override
    public void run() {
        final FragmentHandler handler = this::onFragment;
        while (running) {
            idleStrategy.idle(subscription.poll(handler, FRAGMENT_LIMIT));
        }
    }

    public void stop() { running = false; }

    private void onFragment(final DirectBuffer buffer, final int offset,
                            final int length, final Header header) {
        headerDecoder.wrap(buffer, offset);
        final int templateId = headerDecoder.templateId();
        final int bodyOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH;

        if (templateId == MatchResultDecoder.TEMPLATE_ID) {
            handleMatchResult(buffer, bodyOffset);
        } else if (templateId == OrderBookUpdateDecoder.TEMPLATE_ID) {
            handleOrderBookUpdate(buffer, bodyOffset);
        } else {
            log.debug("Unknown market data templateId={}", templateId);
        }
    }

    private void handleMatchResult(final DirectBuffer buffer, final int offset) {
        matchDecoder.wrap(buffer, offset,
                          MatchResultDecoder.BLOCK_LENGTH,
                          MatchResultDecoder.SCHEMA_VERSION);

        final int  symbolId    = matchDecoder.symbolId();
        final long seqNo       = matchDecoder.sequenceNo();
        final long price       = matchDecoder.price();
        final long qty         = matchDecoder.quantity();
        final byte makerSide   = matchDecoder.makerSide().value();
        final long tsNs        = matchDecoder.timestamp();

        // 成交推送
        tradeDispatcher.onTrade(symbolId, seqNo, price, qty, makerSide, tsNs);

        // Ticker 聚合（turnover = price × qty，精度处理由上层负责）
        tickerAggregator.onTrade(symbolId, price, qty, price * qty, tsNs);
    }

    private void handleOrderBookUpdate(final DirectBuffer buffer, final int offset) {
        obUpdateDecoder.wrap(buffer, offset,
                             OrderBookUpdateDecoder.BLOCK_LENGTH,
                             OrderBookUpdateDecoder.SCHEMA_VERSION);

        final int  symbolId = obUpdateDecoder.symbolId();
        final byte side     = obUpdateDecoder.side().value();
        final long price    = obUpdateDecoder.price();
        final long newQty   = obUpdateDecoder.quantity();
        final long seqNo    = obUpdateDecoder.sequenceNo();
        final long tsNs     = obUpdateDecoder.timestamp();

        depthDispatcher.onOrderBookUpdate(symbolId, side, price, newQty, seqNo,
                                           System.nanoTime());
    }
}
```

---

## 9. PushServiceMain（启动入口）

文件：`push-service/src/main/java/com/trading/push/PushServiceMain.java`

```java
package com.trading.push;

import com.trading.push.aeron.MarketDataSubscriber;
import com.trading.push.dispatcher.DepthDispatcher;
import com.trading.push.dispatcher.TickerAggregator;
import com.trading.push.dispatcher.TradeDispatcher;
import com.trading.push.subscription.SubscriptionRegistry;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;

/**
 * Push Service 启动入口。
 *
 * <p>生产部署：Push Service 与 Gateway 部署在同一机器，共享 Netty 的 WebSocket 连接；
 * SubscriptionRegistry 直接引用 Gateway 的 WebSocketHandler 订阅表。
 *
 * <p>生产环境启动示例：
 * <pre>
 * java -server -Xms2g -Xmx2g -XX:+UseZGC \
 *      -Daeron.dir=/tmp/aeron-push \
 *      com.trading.push.PushServiceMain
 * </pre>
 *
 * @author Reln Ding
 */
public final class PushServiceMain {

    private static final Logger log = LoggerFactory.getLogger(PushServiceMain.class);

    private static final String IPC_CHANNEL       = "aeron:ipc";
    private static final int    MARKET_DATA_STREAM = 4;

    public static void main(final String[] args) throws Exception {
        final String aeronDir = System.getProperty("aeron.dir", "/tmp/aeron-push");

        // 1. 启动 Aeron Media Driver
        final MediaDriver mediaDriver = MediaDriver.launch(new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(false));

        final Aeron aeron = Aeron.connect(
            new Aeron.Context().aeronDirectoryName(aeronDir));

        final Subscription mktSub =
            aeron.addSubscription(IPC_CHANNEL, MARKET_DATA_STREAM);

        // 2. 创建空的 SubscriptionRegistry（生产环境注入 WebSocketHandler 的实现）
        //    此处使用 no-op 实现，实际部署时通过依赖注入替换
        final SubscriptionRegistry noopRegistry = new SubscriptionRegistry() {
            @Override public Set<io.netty.channel.Channel> getDepthSubscribers(int s)  { return Collections.emptySet(); }
            @Override public Set<io.netty.channel.Channel> getTradeSubscribers(int s)  { return Collections.emptySet(); }
            @Override public Set<io.netty.channel.Channel> getTickerSubscribers(int s) { return Collections.emptySet(); }
        };

        final DepthDispatcher  depth   = new DepthDispatcher(noopRegistry);
        final TradeDispatcher  trade   = new TradeDispatcher(noopRegistry);
        final TickerAggregator ticker  = new TickerAggregator(noopRegistry);

        final MarketDataSubscriber subscriber =
            new MarketDataSubscriber(mktSub, depth, trade, ticker);

        // 3. 启动订阅线程
        final Thread subThread = new Thread(subscriber, "market-data-subscriber");
        subThread.setDaemon(false);
        subThread.start();

        log.info("Push Service started, subscribing stream={}", MARKET_DATA_STREAM);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            subscriber.stop();
            aeron.close();
            mediaDriver.close();
            log.info("Push Service shutdown complete.");
        }));

        Thread.currentThread().join();
    }
}
```

---

## 10. Push Service 单元测试

文件：`push-service/src/test/java/com/trading/push/dispatcher/PushDispatcherTest.java`

```java
package com.trading.push.dispatcher;

import com.trading.push.subscription.SubscriptionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Push Service Dispatcher 单元测试。
 *
 * @author Reln Ding
 */
class PushDispatcherTest {

    // ================================================================
    // DepthBook
    // ================================================================

    @Nested @DisplayName("DepthBook")
    class DepthBookTests {

        @Test @DisplayName("applyUpdate 买盘新增档位")
        void applyBidUpdate() {
            final DepthBook book = new DepthBook(1);
            book.applyUpdate((byte) 1, 5000_00L, 100L, 1L);
            final String json = book.getBidsJson(5);
            assertTrue(json.contains("500000"));
            assertTrue(json.contains("100"));
        }

        @Test @DisplayName("applyUpdate qty=0 移除档位")
        void removeLevelOnZeroQty() {
            final DepthBook book = new DepthBook(1);
            book.applyUpdate((byte) 1, 5000_00L, 100L, 1L);
            book.applyUpdate((byte) 1, 5000_00L, 0L, 2L);
            assertEquals("[]", book.getBidsJson(5));
        }

        @Test @DisplayName("买盘多档按价格降序排列")
        void bidsDescendingOrder() {
            final DepthBook book = new DepthBook(1);
            book.applyUpdate((byte) 1, 4999_00L, 200L, 1L);
            book.applyUpdate((byte) 1, 5001_00L, 100L, 2L);
            book.applyUpdate((byte) 1, 5000_00L, 150L, 3L);
            // Top-2 应为 5001, 5000
            final String json = book.getBidsJson(2);
            final int idx5001 = json.indexOf("500100");
            final int idx5000 = json.indexOf("500000");
            assertTrue(idx5001 < idx5000, "5001 should appear before 5000 in bids");
        }

        @Test @DisplayName("卖盘多档按价格升序排列")
        void asksAscendingOrder() {
            final DepthBook book = new DepthBook(1);
            book.applyUpdate((byte) 2, 5002_00L, 100L, 1L);
            book.applyUpdate((byte) 2, 5001_00L, 150L, 2L);
            final String json = book.getAsksJson(2);
            final int idx5001 = json.indexOf("500100");
            final int idx5002 = json.indexOf("500200");
            assertTrue(idx5001 < idx5002, "5001 should appear before 5002 in asks");
        }

        @Test @DisplayName("isEmpty 初始为空，添加后不为空")
        void isEmpty() {
            final DepthBook book = new DepthBook(1);
            assertTrue(book.isEmpty());
            book.applyUpdate((byte) 1, 5000_00L, 100L, 1L);
            assertFalse(book.isEmpty());
        }

        @Test @DisplayName("lastSequenceNo 随每次更新递增")
        void lastSequenceNo() {
            final DepthBook book = new DepthBook(1);
            book.applyUpdate((byte) 1, 5000_00L, 100L, 42L);
            assertEquals(42L, book.getLastSequenceNo());
        }
    }

    // ================================================================
    // TickerAggregator
    // ================================================================

    @Nested @DisplayName("TickerAggregator")
    class TickerAggregatorTests {

        private TickerAggregator aggregator;

        @BeforeEach
        void setUp() {
            final SubscriptionRegistry noopReg = makeNoopRegistry();
            aggregator = new TickerAggregator(noopReg);
        }

        @Test @DisplayName("首次成交后 open/last/high/low 均为成交价")
        void firstTrade() {
            aggregator.onTrade(1, 5000_00L, 100L, 50_000_00L, System.nanoTime());
            final String json = aggregator.getTickerJson(1);
            assertNotNull(json);
            assertTrue(json.contains("\"c\":500000"));
            assertTrue(json.contains("\"o\":500000"));
            assertTrue(json.contains("\"h\":500000"));
            assertTrue(json.contains("\"l\":500000"));
        }

        @Test @DisplayName("多次成交后 high/low 正确更新")
        void highLowUpdate() {
            aggregator.onTrade(1, 5000_00L, 100L, 0L, System.nanoTime());
            aggregator.onTrade(1, 5200_00L, 50L,  0L, System.nanoTime());
            aggregator.onTrade(1, 4900_00L, 30L,  0L, System.nanoTime());
            final String json = aggregator.getTickerJson(1);
            assertTrue(json.contains("\"h\":520000"));
            assertTrue(json.contains("\"l\":490000"));
            assertTrue(json.contains("\"c\":490000"));
        }

        @Test @DisplayName("成交量累加正确")
        void volumeAccumulate() {
            aggregator.onTrade(1, 5000_00L, 100L, 50_000_00L, System.nanoTime());
            aggregator.onTrade(1, 5000_00L, 200L, 100_000_00L, System.nanoTime());
            final String json = aggregator.getTickerJson(1);
            assertTrue(json.contains("\"v\":300"));  // 100+200=300
        }

        @Test @DisplayName("涨跌幅计算正确（上涨 2%）")
        void priceChangePercent() {
            aggregator.onTrade(1, 5000_00L, 1L, 0L, System.nanoTime());
            aggregator.onTrade(1, 5100_00L, 1L, 0L, System.nanoTime());
            final String json = aggregator.getTickerJson(1);
            assertNotNull(json);
            assertTrue(json.contains("\"P\":\"2.00\""), "Expected 2.00%, got: " + json);
        }

        @Test @DisplayName("getTickerJson 无数据返回 null")
        void getTickerJsonNoData() {
            assertNull(aggregator.getTickerJson(999));
        }

        @Test @DisplayName("TickerState.update 正确更新字段")
        void tickerStateUpdate() {
            final TickerAggregator.TickerState state = new TickerAggregator.TickerState(5000L);
            state.update(5100L, 100L, 510_000L);
            assertEquals(5100L, state.lastPrice);
            assertEquals(5100L, state.highPrice);
            assertEquals(5000L, state.lowPrice);
            assertEquals(100L,  state.volume);
            assertEquals(510_000L, state.turnover);
        }
    }

    // ================================================================
    // TradeDispatcher
    // ================================================================

    @Nested @DisplayName("TradeDispatcher")
    class TradeDispatcherTests {

        @Test @DisplayName("onTrade 向订阅者推送包含正确字段的 JSON")
        void onTradePublishesJson() {
            final EmbeddedChannel ch = new EmbeddedChannel();
            final SubscriptionRegistry reg = makeTradeRegistry(1, ch);
            final TradeDispatcher dispatcher = new TradeDispatcher(reg);

            dispatcher.onTrade(1, 100L, 5000_00L, 100_000L, (byte) 2, 1_700_000_000_000_000_000L);

            final TextWebSocketFrame frame = ch.readOutbound();
            assertNotNull(frame, "No frame written");
            final String text = frame.text();
            assertTrue(text.contains("\"e\":\"trade\""));
            assertTrue(text.contains("\"s\":1"));
            assertTrue(text.contains("\"p\":500000"));
            assertTrue(text.contains("\"q\":100000"));
            assertTrue(text.contains("\"side\":2"));
        }

        @Test @DisplayName("无订阅者时不推送（不报错）")
        void noSubscribersNoError() {
            final TradeDispatcher dispatcher = new TradeDispatcher(makeNoopRegistry());
            assertDoesNotThrow(() ->
                dispatcher.onTrade(1, 1L, 5000_00L, 100L, (byte) 1, System.nanoTime()));
        }
    }

    // ================================================================
    // DepthDispatcher
    // ================================================================

    @Nested @DisplayName("DepthDispatcher")
    class DepthDispatcherTests {

        @Test @DisplayName("onOrderBookUpdate 向订阅者推送差量 JSON")
        void onUpdatePublishesDelta() {
            final EmbeddedChannel ch = new EmbeddedChannel();
            final SubscriptionRegistry reg = makeDepthRegistry(1, ch);
            final DepthDispatcher dispatcher = new DepthDispatcher(reg);

            dispatcher.onOrderBookUpdate(1, (byte) 2, 5001_00L, 150L, 1L, 0L);

            final TextWebSocketFrame frame = ch.readOutbound();
            assertNotNull(frame);
            final String text = frame.text();
            assertTrue(text.contains("\"e\":\"depthUpdate\""));
            assertTrue(text.contains("\"s\":1"));
            assertTrue(text.contains("500100"));
            assertTrue(text.contains("150"));
        }

        @Test @DisplayName("qty=0 时差量 JSON 中数量为 0")
        void zeroQtyInDelta() {
            final EmbeddedChannel ch = new EmbeddedChannel();
            final SubscriptionRegistry reg = makeDepthRegistry(1, ch);
            final DepthDispatcher dispatcher = new DepthDispatcher(reg);

            dispatcher.onOrderBookUpdate(1, (byte) 1, 5000_00L, 0L, 2L, 0L);

            final TextWebSocketFrame frame = ch.readOutbound();
            assertNotNull(frame);
            assertTrue(frame.text().contains(",0]"));
        }
    }

    // ================================================================
    // 工具方法
    // ================================================================

    private static SubscriptionRegistry makeNoopRegistry() {
        return new SubscriptionRegistry() {
            @Override public Set<Channel> getDepthSubscribers(int s)  { return Collections.emptySet(); }
            @Override public Set<Channel> getTradeSubscribers(int s)  { return Collections.emptySet(); }
            @Override public Set<Channel> getTickerSubscribers(int s) { return Collections.emptySet(); }
        };
    }

    private static SubscriptionRegistry makeTradeRegistry(final int symbolId,
                                                           final Channel ch) {
        return new SubscriptionRegistry() {
            @Override public Set<Channel> getDepthSubscribers(int s)  { return Collections.emptySet(); }
            @Override public Set<Channel> getTradeSubscribers(int s)  { return s == symbolId ? Set.of(ch) : Collections.emptySet(); }
            @Override public Set<Channel> getTickerSubscribers(int s) { return Collections.emptySet(); }
        };
    }

    private static SubscriptionRegistry makeDepthRegistry(final int symbolId,
                                                           final Channel ch) {
        return new SubscriptionRegistry() {
            @Override public Set<Channel> getDepthSubscribers(int s)  { return s == symbolId ? Set.of(ch) : Collections.emptySet(); }
            @Override public Set<Channel> getTradeSubscribers(int s)  { return Collections.emptySet(); }
            @Override public Set<Channel> getTickerSubscribers(int s) { return Collections.emptySet(); }
        };
    }
}
```

### 10.1 运行单元测试

```bash
cd trading-platform
mvn test -pl push-service -Dtest=PushDispatcherTest -Dcheckstyle.skip=true
# 期望：Tests run: 18, Failures: 0, Errors: 0
```

---

## Part 3 完成检查清单

- [ ] `DepthBook`：买盘降序、卖盘升序；qty=0 移除档位；Top-N JSON 格式正确
- [ ] `DepthDispatcher`：差量 JSON 格式含 `e/s/seq/b/a`；定时全量（1s）逻辑分支覆盖
- [ ] `TradeDispatcher`：成交 JSON 含 `e/s/seq/p/q/side/t`；无订阅者不报错
- [ ] `TickerAggregator`：首次成交四价相同；high/low 正确更新；涨跌幅计算精度正确；无数据返回 null
- [ ] `MarketDataSubscriber`：MatchResult 路由到 Trade+Ticker；OrderBookUpdate 路由到 Depth
- [ ] `PushDispatcherTest` **18 个测试通过**
- [ ] 含 Part 1-2 合计 **58 个测试，0 Failures，0 Errors**

---

## 下一步：Part 4

Part 3 完成后，进入 **Part 4：Journal Service**，包括：

1. `journal-service` POM 与模块结构
2. `JournalEventSubscriber`：订阅 Aeron IPC stream=5
3. `MappedFileJournalWriter`：Memory-Mapped 顺序写（`FileChannel.map`）
4. `JournalRotationManager`：按日期/大小滚动新建文件
5. `JournalReplayService`：从指定序列号回放日志（用于故障恢复）
6. Journal 写入 + 回放单元测试
