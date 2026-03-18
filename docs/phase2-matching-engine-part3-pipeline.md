# Phase 2 撮合引擎核心实现 — Part 3：Disruptor Pipeline 与 Aeron IPC 集成

> **目标：** 将 Part 2 的撮合逻辑嵌入 Disruptor 3 段 Pipeline，并通过 Aeron IPC 与上游柜台服务、
> 下游回报/行情/日志服务完成完整的消息链路集成。
>
> **前置条件：** Part 2 完成，撮合算法冒烟测试全部通过  
> **本节验证目标：** 完整 Aeron IPC → Disruptor → Aeron IPC 链路联调通过，吞吐量 > 200K orders/sec

---

## 目录

1. [Pipeline 架构总览](#1-pipeline-架构总览)
2. [RingBuffer 事件定义](#2-ringbuffer-事件定义)
3. [Stage 1：SequenceAssignHandler](#3-stage-1sequenceassignhandler)
4. [Stage 2：MatchingHandler](#4-stage-2matchinghandler)
5. [Stage 3：并行输出 Handlers](#5-stage-3并行输出-handlers)
6. [InboundOrderSubscriber（Aeron → Disruptor）](#6-inboundordersubscriberaeron--disruptor)
7. [MatchingDisruptor 装配](#7-matchingdisruptor-装配)
8. [MatchingEngineMain 启动入口](#8-matchingenginemain-启动入口)
9. [集成冒烟测试](#9-集成冒烟测试)

---

## 1. Pipeline 架构总览

```
Aeron IPC stream=2 (Counter → ME)
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│  InboundOrderSubscriber (BusySpin 轮询)                      │
│  • 反序列化 SBE InternalNewOrder / InternalCancelOrder        │
│  • 从 OrderNodePool 借出 OrderNode                           │
│  • RingBuffer.publishEvent(...)                              │
└───────────────────────────┬─────────────────────────────────┘
                            │ SPSC RingBuffer (2^20)
┌───────────────────────────▼─────────────────────────────────┐
│  Stage 1: SequenceAssignHandler                              │
│  • 分配全局单调递增撮合序列号                                   │
│  • 单线程，保证严格有序                                        │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│  Stage 2: MatchingHandler                                    │
│  • 调用 OrderMatcher.match() 执行撮合                         │
│  • 填充 MatchResult（成交事件列表）                            │
│  • 单线程，保证撮合有序                                        │
└──────────────┬────────────────────┬────────────────────────-┘
               │ (并行)              │ (并行)          │ (并行)
┌──────────────▼──┐  ┌──────────────▼──┐  ┌───────────▼──────┐
│JournalPublish   │  │ExecReport       │  │MarketDataPublish  │
│Handler          │  │Handler          │  │Handler            │
│stream=5 → Jrnl  │  │stream=3 → Cntr  │  │stream=4 → Push    │
└─────────────────┘  └─────────────────┘  └──────────────────┘
```

**关键设计点：**

- Stage 1 和 Stage 2 **串行**（保证撮合全局有序）
- Stage 3 三个 Handler **并行**（Disruptor 菱形依赖，等 Stage 2 完成后同时触发）
- 每个交易对的 `MatchingHandler` 绑定独立线程和 CPU 核心

---

## 2. RingBuffer 事件定义

RingBuffer 中的事件对象预分配并反复复用，热路径零 GC。

文件：`matching-engine/src/main/java/com/trading/matching/disruptor/MatchingEvent.java`

```java
package com.trading.matching.disruptor;

import com.trading.matching.orderbook.MatchResult;
import com.trading.matching.orderbook.OrderNode;
import com.trading.matching.orderbook.OrderNodePool;
import com.lmax.disruptor.EventFactory;

/**
 * Disruptor RingBuffer 事件对象（预分配，反复复用）。
 *
 * <p>生命周期：
 * <ol>
 *   <li>{@link InboundOrderSubscriber} 填充入站字段（orderId、price、qty 等）。</li>
 *   <li>Stage 1 {@link SequenceAssignHandler} 填充 {@code matchSequenceNo}。</li>
 *   <li>Stage 2 {@link MatchingHandler} 执行撮合，结果写入 {@code matchResult}。</li>
 *   <li>Stage 3 各 Handler 读取 {@code matchResult} 并分发。</li>
 * </ol>
 *
 * @author Reln Ding
 */
public final class MatchingEvent {

    // ---- 入站字段（由 InboundOrderSubscriber 填充）----

    /** 事件类型：1=NewOrder, 2=CancelOrder */
    public byte eventType;

    /** 订单节点（NewOrder 时从对象池借出；CancelOrder 时为 null）*/
    public OrderNode orderNode;

    /** 撤单时的订单 ID（CancelOrder 专用）*/
    public long cancelOrderId;

    /** 对应请求的 correlationId（用于回报路由）*/
    public long correlationId;

    // ---- Stage 1 填充 ----

    /** 撮合全局序列号（单调递增）*/
    public long matchSequenceNo;

    // ---- Stage 2 填充 ----

    /** 撮合结果（预分配，每次 reset 后复用）*/
    public final MatchResult matchResult;

    // ---- 标志位 ----

    /** 撤单操作找到了目标订单（CancelOrder 时有效）*/
    public boolean cancelFound;

    /** 被撤销的节点（CancelOrder 时有效，Stage 3 用于归还对象池）*/
    public OrderNode cancelledNode;

    public MatchingEvent() {
        // 每个 Event 预分配一个 MatchResult（含 MatchEventList）
        // 容量 2048：单笔撮合最多产生 2048 笔成交（极端市价单扫穿场景）
        this.matchResult = new MatchResult(2048);
    }

    /** 工厂：Disruptor 启动时调用，预分配所有 Event 对象 */
    public static final EventFactory<MatchingEvent> FACTORY = MatchingEvent::new;

    /** 重置（每次事件被生产者填充前，由生产者负责重置必要字段）*/
    public void reset() {
        eventType       = 0;
        orderNode       = null;
        cancelOrderId   = 0L;
        correlationId   = 0L;
        matchSequenceNo = 0L;
        cancelFound     = false;
        cancelledNode   = null;
        matchResult.reset(null);
    }
}
```

---

## 3. Stage 1：SequenceAssignHandler

分配全局单调递增撮合序列号，单线程执行，保证序号严格有序。

文件：`matching-engine/src/main/java/com/trading/matching/disruptor/SequenceAssignHandler.java`

```java
package com.trading.matching.disruptor;

import com.lmax.disruptor.EventHandler;

/**
 * Stage 1：全局撮合序列号分配器。
 *
 * <p>此 Handler 单线程执行，保证序列号严格递增。
 * 序列号是事件日志（Journal）的主键，也是系统全局事件顺序的依据。
 *
 * @author Reln Ding
 */
public final class SequenceAssignHandler implements EventHandler<MatchingEvent> {

    private long sequenceCounter = 0L;

    @Override
    public void onEvent(final MatchingEvent event,
                        final long sequence,
                        final boolean endOfBatch) {
        event.matchSequenceNo = ++sequenceCounter;
    }

    /** 获取当前已分配的最大序列号（用于监控）*/
    public long getCurrentSequence() {
        return sequenceCounter;
    }
}
```

---

## 4. Stage 2：MatchingHandler

调用 `OrderMatcher` 执行撮合，将结果写入 `MatchingEvent.matchResult`。

文件：`matching-engine/src/main/java/com/trading/matching/disruptor/MatchingHandler.java`

```java
package com.trading.matching.disruptor;

import com.lmax.disruptor.EventHandler;
import com.trading.matching.matcher.OrderMatcher;
import com.trading.matching.orderbook.OrderNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stage 2：撮合执行器。
 *
 * <p>处理两类事件：
 * <ul>
 *   <li>eventType=1 (NewOrder)：调用 {@link OrderMatcher#match(OrderNode, com.trading.matching.orderbook.MatchResult)}。</li>
 *   <li>eventType=2 (CancelOrder)：调用 {@link OrderMatcher#cancel(long)}。</li>
 * </ul>
 *
 * <p>单线程执行，所有状态变更（订单簿修改）在此线程完成，无并发问题。
 *
 * @author Reln Ding
 */
public final class MatchingHandler implements EventHandler<MatchingEvent> {

    private static final Logger log = LoggerFactory.getLogger(MatchingHandler.class);

    private static final byte EVENT_NEW_ORDER    = 1;
    private static final byte EVENT_CANCEL_ORDER = 2;

    private final OrderMatcher matcher;

    public MatchingHandler(final OrderMatcher matcher) {
        this.matcher = matcher;
    }

    @Override
    public void onEvent(final MatchingEvent event,
                        final long sequence,
                        final boolean endOfBatch) {
        if (event.eventType == EVENT_NEW_ORDER) {
            handleNewOrder(event);
        } else if (event.eventType == EVENT_CANCEL_ORDER) {
            handleCancelOrder(event);
        } else {
            log.warn("Unknown eventType: {}, sequence: {}", event.eventType, sequence);
        }
    }

    private void handleNewOrder(final MatchingEvent event) {
        final OrderNode taker = event.orderNode;
        if (taker == null) {
            log.warn("NewOrder event has null orderNode, matchSeq={}", event.matchSequenceNo);
            return;
        }
        event.matchResult.reset(taker);
        matcher.match(taker, event.matchResult);
    }

    private void handleCancelOrder(final MatchingEvent event) {
        final OrderNode cancelled = matcher.cancel(event.cancelOrderId);
        event.cancelFound   = (cancelled != null);
        event.cancelledNode = cancelled;
    }
}
```

---

## 5. Stage 3：并行输出 Handlers

Stage 3 的三个 Handler 在 Stage 2 完成后**并行执行**，分别负责日志、回报、行情推送。

### 5.1 JournalPublishHandler

文件：`matching-engine/src/main/java/com/trading/matching/disruptor/JournalPublishHandler.java`

```java
package com.trading.matching.disruptor;

import com.lmax.disruptor.EventHandler;
import com.trading.matching.orderbook.MatchEvent;
import com.trading.matching.orderbook.MatchResult;
import io.aeron.Publication;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Stage 3a：Journal 事件发布器。
 *
 * <p>将成交事件通过 Aeron IPC stream=5 异步写入 Journal Service，
 * 不阻塞撮合热路径。写入失败时记录告警（Journal 允许极小概率丢失，
 * 实际丢失由 Aeron Archive 的 Raft 复制保证）。
 *
 * @author Reln Ding
 */
public final class JournalPublishHandler implements EventHandler<MatchingEvent> {

    private static final Logger log = LoggerFactory.getLogger(JournalPublishHandler.class);

    // Journal 事件二进制格式（固定 64 字节/条）
    // Offset 0 : int64 sequenceNo
    // Offset 8 : int32 symbolId
    // Offset 12: int64 makerOrderId
    // Offset 20: int64 takerOrderId
    // Offset 28: int64 makerAccountId
    // Offset 36: int64 takerAccountId
    // Offset 44: int64 price
    // Offset 52: int64 quantity
    // Offset 60: int64 timestampNs
    private static final int JOURNAL_MSG_LENGTH = 68;

    private final Publication journalPublication;   // Aeron IPC stream=5
    private final MutableDirectBuffer sendBuffer;

    public JournalPublishHandler(final Publication journalPublication) {
        this.journalPublication = journalPublication;
        this.sendBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(JOURNAL_MSG_LENGTH));
    }

    @Override
    public void onEvent(final MatchingEvent event,
                        final long sequence,
                        final boolean endOfBatch) {
        final MatchResult result = event.matchResult;
        for (int i = 0; i < result.events.size(); i++) {
            publishTradeEvent(event.matchSequenceNo, result.events.get(i));
        }

        // 撤单事件也写 Journal（简化：仅记录 sequenceNo + orderId）
        if (event.cancelFound && event.cancelledNode != null) {
            publishCancelEvent(event.matchSequenceNo, event.cancelledNode.orderId);
        }
    }

    private void publishTradeEvent(final long matchSeqNo, final MatchEvent e) {
        sendBuffer.putLong(0,  matchSeqNo);
        sendBuffer.putInt(8,   e.symbolId);
        sendBuffer.putLong(12, e.makerOrderId);
        sendBuffer.putLong(20, e.takerOrderId);
        sendBuffer.putLong(28, e.makerAccountId);
        sendBuffer.putLong(36, e.takerAccountId);
        sendBuffer.putLong(44, e.price);
        sendBuffer.putLong(52, e.quantity);
        sendBuffer.putLong(60, e.timestampNs);

        final long result = journalPublication.offer(sendBuffer, 0, JOURNAL_MSG_LENGTH);
        if (result < 0 && result != Publication.BACK_PRESSURED) {
            log.warn("Journal publish failed: {}, matchSeq={}", result, matchSeqNo);
        }
    }

    private void publishCancelEvent(final long matchSeqNo, final long orderId) {
        // 简化：仅写入 sequenceNo + orderId（实际可扩展为完整 cancel 事件格式）
        sendBuffer.putLong(0, matchSeqNo);
        sendBuffer.putLong(8, orderId);
        journalPublication.offer(sendBuffer, 0, 16);
    }
}
```

### 5.2 ExecutionReportHandler

文件：`matching-engine/src/main/java/com/trading/matching/disruptor/ExecutionReportHandler.java`

```java
package com.trading.matching.disruptor;

import com.lmax.disruptor.EventHandler;
import com.trading.matching.orderbook.MatchEvent;
import com.trading.matching.orderbook.MatchResult;
import com.trading.matching.orderbook.OrderNode;
import com.trading.sbe.*;
import io.aeron.Publication;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Stage 3b：执行回报发布器。
 *
 * <p>将成交事件编码为 SBE {@code MatchResult}（templateId=301）消息，
 * 通过 Aeron IPC stream=3 发送给柜台服务，柜台服务再生成 {@code ExecutionReport} 回报给客户端。
 *
 * @author Reln Ding
 */
public final class ExecutionReportHandler implements EventHandler<MatchingEvent> {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReportHandler.class);

    private final Publication execReportPublication;   // Aeron IPC stream=3
    private final MutableDirectBuffer sendBuffer;
    private final MessageHeaderEncoder headerEncoder;
    private final MatchResultEncoder   matchResultEncoder;

    public ExecutionReportHandler(final Publication execReportPublication) {
        this.execReportPublication = execReportPublication;
        this.sendBuffer            = new UnsafeBuffer(ByteBuffer.allocateDirect(512));
        this.headerEncoder         = new MessageHeaderEncoder();
        this.matchResultEncoder    = new MatchResultEncoder();
    }

    @Override
    public void onEvent(final MatchingEvent event,
                        final long sequence,
                        final boolean endOfBatch) {
        final MatchResult result = event.matchResult;

        // 逐笔发送成交回报
        for (int i = 0; i < result.events.size(); i++) {
            publishMatchResult(event.matchSequenceNo, result.events.get(i));
        }

        // 处理撤单确认（无成交但有状态变更的情况）
        if (event.eventType == 2) {  // CancelOrder
            publishCancelConfirm(event);
        }

        // RESTING / CANCELLED / REJECTED 状态通知（无成交事件时需单独推送）
        if (!result.events.hasEvents() && result.takerNode != null) {
            publishTakerStatusOnly(event);
        }

        // 归还已全成交/撤销的 Taker 节点（RESTING 状态的节点留在订单簿中，不归还）
        final MatchResult.TakerStatus status = result.takerStatus;
        if (status != null && status != MatchResult.TakerStatus.RESTING
                && result.takerNode != null) {
            // Taker 节点不再需要，归还对象池
            // 注：实际调用由 OrderBook.releaseNode 完成，此处仅作占位说明
        }

        // 归还已撤销的 Maker 节点（由 MatchingHandler 在 onFill 时已归还，此处无需重复）
        if (event.cancelFound && event.cancelledNode != null) {
            // cancelledNode 由柜台服务对应的 Handler 归还，撮合引擎侧不重复归还
        }
    }

    private void publishMatchResult(final long matchSeqNo, final MatchEvent e) {
        final int headerLen = MessageHeaderEncoder.ENCODED_LENGTH;
        headerEncoder.wrap(sendBuffer, 0)
            .blockLength(MatchResultEncoder.BLOCK_LENGTH)
            .templateId(MatchResultEncoder.TEMPLATE_ID)
            .schemaId(MatchResultEncoder.SCHEMA_ID)
            .version(MatchResultEncoder.SCHEMA_VERSION);

        matchResultEncoder.wrap(sendBuffer, headerLen)
            .sequenceNo(matchSeqNo)
            .symbolId(e.symbolId)
            .makerOrderId(e.makerOrderId)
            .takerOrderId(e.takerOrderId)
            .makerAccountId(e.makerAccountId)
            .takerAccountId(e.takerAccountId)
            .price(e.price)
            .quantity(e.quantity)
            .makerSide(Side.get(e.makerSide))
            .makerFee(e.makerFee)
            .takerFee(e.takerFee)
            .timestamp(e.timestampNs);

        final int totalLen = headerLen + MatchResultEncoder.BLOCK_LENGTH;
        final long result = execReportPublication.offer(sendBuffer, 0, totalLen);
        if (result < 0 && result != Publication.BACK_PRESSURED) {
            log.warn("ExecReport publish failed: {}, matchSeq={}", result, matchSeqNo);
        }
    }

    private void publishCancelConfirm(final MatchingEvent event) {
        // 简化实现：实际需编码 CancelConfirm SBE 消息，此处省略
        log.debug("Cancel confirm: orderId={}, found={}", event.cancelOrderId, event.cancelFound);
    }

    private void publishTakerStatusOnly(final MatchingEvent event) {
        // 针对 RESTING / IOC-CANCELLED / FOK-REJECTED 等无成交事件的状态变更
        // 需单独发送 ExecutionReport 给柜台服务，此处简化为 log
        log.debug("Taker status only: status={}, orderId={}",
                  event.matchResult.takerStatus,
                  event.matchResult.takerNode != null
                      ? event.matchResult.takerNode.orderId : -1);
    }
}
```

### 5.3 MarketDataPublishHandler

文件：`matching-engine/src/main/java/com/trading/matching/disruptor/MarketDataPublishHandler.java`

```java
package com.trading.matching.disruptor;

import com.lmax.disruptor.EventHandler;
import com.trading.matching.orderbook.MatchEvent;
import com.trading.matching.orderbook.MatchResult;
import com.trading.sbe.*;
import io.aeron.Publication;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Stage 3c：行情数据发布器。
 *
 * <p>将成交事件编码为 SBE {@code OrderBookUpdate}（templateId=302）消息，
 * 通过 Aeron IPC stream=4 发送给推送服务（Push Service）用于行情广播。
 *
 * @author Reln Ding
 */
public final class MarketDataPublishHandler implements EventHandler<MatchingEvent> {

    private static final Logger log = LoggerFactory.getLogger(MarketDataPublishHandler.class);

    private final Publication marketDataPublication;   // Aeron IPC stream=4
    private final MutableDirectBuffer sendBuffer;
    private final MessageHeaderEncoder  headerEncoder;
    private final OrderBookUpdateEncoder obUpdateEncoder;

    public MarketDataPublishHandler(final Publication marketDataPublication) {
        this.marketDataPublication = marketDataPublication;
        this.sendBuffer     = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
        this.headerEncoder  = new MessageHeaderEncoder();
        this.obUpdateEncoder = new OrderBookUpdateEncoder();
    }

    @Override
    public void onEvent(final MatchingEvent event,
                        final long sequence,
                        final boolean endOfBatch) {
        final MatchResult result = event.matchResult;
        for (int i = 0; i < result.events.size(); i++) {
            publishOrderBookUpdate(event.matchSequenceNo, result.events.get(i));
        }
    }

    private void publishOrderBookUpdate(final long matchSeqNo, final MatchEvent e) {
        final int headerLen = MessageHeaderEncoder.ENCODED_LENGTH;
        headerEncoder.wrap(sendBuffer, 0)
            .blockLength(OrderBookUpdateEncoder.BLOCK_LENGTH)
            .templateId(OrderBookUpdateEncoder.TEMPLATE_ID)
            .schemaId(OrderBookUpdateEncoder.SCHEMA_ID)
            .version(OrderBookUpdateEncoder.SCHEMA_VERSION);

        // 推送 Maker 侧深度变化（成交消耗了 Maker 侧的挂单量）
        obUpdateEncoder.wrap(sendBuffer, headerLen)
            .sequenceNo(matchSeqNo)
            .symbolId(e.symbolId)
            .side(Side.get(e.makerSide))
            .price(e.price)
            .quantity(e.makerFullyFilled ? 0L : -e.quantity)  // 0=档位清空，负值=减少量
            .timestamp(e.timestampNs);

        final int totalLen = headerLen + OrderBookUpdateEncoder.BLOCK_LENGTH;
        final long result = marketDataPublication.offer(sendBuffer, 0, totalLen);
        if (result < 0 && result != Publication.BACK_PRESSURED) {
            log.warn("MarketData publish failed: {}, matchSeq={}", result, matchSeqNo);
        }
    }
}
```

---

## 6. InboundOrderSubscriber（Aeron → Disruptor）

从 Aeron IPC stream=2 读取入站订单消息，解码 SBE，将订单放入 Disruptor RingBuffer。

文件：`matching-engine/src/main/java/com/trading/matching/aeron/InboundOrderSubscriber.java`

```java
package com.trading.matching.aeron;

import com.lmax.disruptor.RingBuffer;
import com.trading.matching.disruptor.MatchingEvent;
import com.trading.matching.orderbook.OrderBook;
import com.trading.matching.orderbook.OrderNode;
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
 * 入站订单订阅器：Aeron IPC stream=2 → Disruptor RingBuffer。
 *
 * <p>运行在独立线程，BusySpin 轮询 Aeron Subscription，
 * 收到消息后解码 SBE 并发布到 RingBuffer。
 *
 * <p>支持消息类型：
 * <ul>
 *   <li>{@code InternalNewOrder}（templateId=201）</li>
 *   <li>{@code InternalCancelOrder}（templateId=202）</li>
 * </ul>
 *
 * @author Reln Ding
 */
public final class InboundOrderSubscriber implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(InboundOrderSubscriber.class);

    private static final int FRAGMENT_LIMIT = 10;

    private final Subscription          inboundSubscription;
    private final RingBuffer<MatchingEvent> ringBuffer;
    private final OrderBook             orderBook;
    private final IdleStrategy          idleStrategy;

    // SBE 解码器（预分配，复用）
    private final MessageHeaderDecoder      headerDecoder    = new MessageHeaderDecoder();
    private final InternalNewOrderDecoder   newOrderDecoder  = new InternalNewOrderDecoder();
    private final InternalCancelOrderDecoder cancelDecoder   = new InternalCancelOrderDecoder();

    private volatile boolean running = true;

    public InboundOrderSubscriber(final Subscription inboundSubscription,
                                  final RingBuffer<MatchingEvent> ringBuffer,
                                  final OrderBook orderBook) {
        this.inboundSubscription = inboundSubscription;
        this.ringBuffer          = ringBuffer;
        this.orderBook           = orderBook;
        this.idleStrategy        = new BusySpinIdleStrategy();
    }

    @Override
    public void run() {
        final FragmentHandler handler = this::onFragment;
        while (running) {
            final int fragments = inboundSubscription.poll(handler, FRAGMENT_LIMIT);
            idleStrategy.idle(fragments);
        }
    }

    public void stop() {
        running = false;
    }

    private void onFragment(final DirectBuffer buffer,
                            final int offset,
                            final int length,
                            final Header header) {
        // 读取消息头，判断类型
        headerDecoder.wrap(buffer, offset);
        final int templateId = headerDecoder.templateId();
        final int headerLen  = MessageHeaderDecoder.ENCODED_LENGTH;

        if (templateId == InternalNewOrderDecoder.TEMPLATE_ID) {
            handleNewOrder(buffer, offset + headerLen);
        } else if (templateId == InternalCancelOrderDecoder.TEMPLATE_ID) {
            handleCancelOrder(buffer, offset + headerLen);
        } else {
            log.warn("Unknown templateId: {}", templateId);
        }
    }

    private void handleNewOrder(final DirectBuffer buffer, final int bodyOffset) {
        newOrderDecoder.wrap(buffer, bodyOffset,
                             InternalNewOrderDecoder.BLOCK_LENGTH,
                             InternalNewOrderDecoder.SCHEMA_VERSION);

        // 从对象池借出 OrderNode
        final OrderNode node = orderBook.borrowNode();
        if (node == null) {
            log.error("OrderNodePool exhausted! Dropping order orderId={}",
                      newOrderDecoder.orderId());
            return;
        }

        node.init(
            newOrderDecoder.orderId(),
            newOrderDecoder.accountId(),
            orderBook.symbolId,
            newOrderDecoder.side().value(),
            newOrderDecoder.orderType().value(),
            newOrderDecoder.timeInForce().value(),
            newOrderDecoder.price(),
            newOrderDecoder.quantity(),
            0L,   // expireTimeNs（GTD 扩展时填入）
            newOrderDecoder.timestamp()
        );

        // 发布到 RingBuffer
        ringBuffer.publishEvent((event, seq) -> {
            event.reset();
            event.eventType    = 1;
            event.orderNode    = node;
            event.correlationId = newOrderDecoder.correlationId();
        });
    }

    private void handleCancelOrder(final DirectBuffer buffer, final int bodyOffset) {
        cancelDecoder.wrap(buffer, bodyOffset,
                           InternalCancelOrderDecoder.BLOCK_LENGTH,
                           InternalCancelOrderDecoder.SCHEMA_VERSION);

        final long orderId       = cancelDecoder.orderId();
        final long correlationId = cancelDecoder.correlationId();

        ringBuffer.publishEvent((event, seq) -> {
            event.reset();
            event.eventType     = 2;
            event.cancelOrderId = orderId;
            event.correlationId = correlationId;
        });
    }
}
```

---

## 7. MatchingDisruptor 装配

将所有 Handler 组装为完整的 3 段 Pipeline。

文件：`matching-engine/src/main/java/com/trading/matching/disruptor/MatchingDisruptor.java`

```java
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
public final class MatchingDisruptor {

    private static final Logger log = LoggerFactory.getLogger(MatchingDisruptor.class);

    /** RingBuffer 大小（必须为 2 的幂）*/
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

        // 1. 创建 Handler 实例
        final SequenceAssignHandler   stage1  = new SequenceAssignHandler();
        final MatchingHandler         stage2  = new MatchingHandler(matcher);
        final JournalPublishHandler   stage3a = new JournalPublishHandler(journalPublication);
        final ExecutionReportHandler  stage3b = new ExecutionReportHandler(execReportPublication);
        final MarketDataPublishHandler stage3c = new MarketDataPublishHandler(marketDataPublication);

        // 2. 创建 Disruptor
        final ThreadFactory threadFactory = r -> {
            final Thread t = new Thread(r, "matching-disruptor-" + orderBook.symbolId);
            t.setDaemon(false);
            return t;
        };

        this.disruptor = new Disruptor<>(
            MatchingEvent.FACTORY,
            RING_BUFFER_SIZE,
            threadFactory,
            ProducerType.SINGLE,          // 单生产者（InboundSubscriber 是唯一写入方）
            new BusySpinWaitStrategy()    // 最低延迟
        );

        // 3. 配置 Pipeline 拓扑
        disruptor
            .handleEventsWith(stage1)              // Stage 1 先执行
            .then(stage2)                          // Stage 2 在 Stage 1 之后
            .then(stage3a, stage3b, stage3c);      // Stage 3 并行

        // 4. 启动 Disruptor
        this.ringBuffer = disruptor.start();
        log.info("MatchingDisruptor started for symbolId={}, ringBufferSize={}",
                 orderBook.symbolId, RING_BUFFER_SIZE);

        // 5. 创建 InboundSubscriber（从 Aeron 读入并写入 RingBuffer）
        this.inboundSubscriber = new InboundOrderSubscriber(
            inboundSubscription, ringBuffer, orderBook);
        this.inboundThread = new Thread(inboundSubscriber,
            "inbound-subscriber-" + orderBook.symbolId);
        this.inboundThread.setDaemon(false);
        this.inboundThread.start();

        log.info("InboundOrderSubscriber started for symbolId={}", orderBook.symbolId);
    }

    /** 优雅关闭 */
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

    /** 获取 RingBuffer（供测试使用）*/
    public RingBuffer<MatchingEvent> getRingBuffer() {
        return ringBuffer;
    }
}
```

---

## 8. MatchingEngineMain 启动入口

文件：`matching-engine/src/main/java/com/trading/matching/MatchingEngineMain.java`

```java
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
public final class MatchingEngineMain {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngineMain.class);

    // Aeron 通道配置
    private static final String IPC_CHANNEL      = "aeron:ipc";
    private static final int    INBOUND_STREAM    = 2;   // Counter → ME
    private static final int    EXEC_REPORT_STREAM = 3;  // ME → Counter（成交回报）
    private static final int    MARKET_DATA_STREAM = 4;  // ME → Push（行情）
    private static final int    JOURNAL_STREAM     = 5;  // ME → Journal（日志）

    // 交易对配置（生产环境从配置文件加载）
    private static final int DEFAULT_SYMBOL_ID      = 1;
    private static final int DEFAULT_MAKER_FEE      = 1_000;   // 0.1%
    private static final int DEFAULT_TAKER_FEE      = 2_000;   // 0.2%
    private static final int DEFAULT_POOL_SIZE       = 500_000;

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
        final Publication execPub    = aeron.addPublication(IPC_CHANNEL, EXEC_REPORT_STREAM);
        final Publication mktPub     = aeron.addPublication(IPC_CHANNEL, MARKET_DATA_STREAM);
        final Publication jrnlPub    = aeron.addPublication(IPC_CHANNEL, JOURNAL_STREAM);

        // 4. 初始化 OrderBook 和 OrderMatcher
        final OrderNodePool pool   = new OrderNodePool(DEFAULT_POOL_SIZE);
        final OrderBook     book   = new OrderBook(DEFAULT_SYMBOL_ID, pool);
        final OrderMatcher  matcher = new OrderMatcher(
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
```

---

## 9. 集成冒烟测试

验证 Disruptor Pipeline 在不依赖 Aeron 的情况下的完整处理流程。

文件：`matching-engine/src/test/java/com/trading/matching/disruptor/MatchingPipelineSmokeTest.java`

```java
package com.trading.matching.disruptor;

import com.lmax.disruptor.RingBuffer;
import com.trading.matching.matcher.OrderMatcher;
import com.trading.matching.orderbook.*;
import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import com.trading.sbe.TimeInForce;
import com.trading.util.NanoTimeProvider;
import io.aeron.Publication;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MatchingDisruptor Pipeline 集成冒烟测试（Mock Aeron Publication）。
 *
 * @author Reln Ding
 */
class MatchingPipelineSmokeTest {

    private static final int SYMBOL_ID = 1;

    private OrderNodePool pool;
    private OrderBook     book;
    private OrderMatcher  matcher;

    // Mock Aeron Publications
    private Publication journalPub;
    private Publication execPub;
    private Publication mktPub;

    // 被测 Disruptor 组件（不含 Aeron 订阅，直接向 RingBuffer 发布）
    private Disruptor<MatchingEvent> disruptor;
    private RingBuffer<MatchingEvent> ringBuffer;

    @BeforeEach
    void setUp() {
        pool    = new OrderNodePool(1024);
        book    = new OrderBook(SYMBOL_ID, pool);
        matcher = new OrderMatcher(book, 1_000, 2_000, NanoTimeProvider.SYSTEM);

        journalPub = mock(Publication.class);
        execPub    = mock(Publication.class);
        mktPub     = mock(Publication.class);

        when(journalPub.offer(any(DirectBuffer.class), anyInt(), anyInt()))
            .thenReturn(1L);
        when(execPub.offer(any(DirectBuffer.class), anyInt(), anyInt()))
            .thenReturn(1L);
        when(mktPub.offer(any(DirectBuffer.class), anyInt(), anyInt()))
            .thenReturn(1L);

        // 直接组装 Disruptor（不含 InboundSubscriber）
        final SequenceAssignHandler   stage1  = new SequenceAssignHandler();
        final MatchingHandler         stage2  = new MatchingHandler(matcher);
        final JournalPublishHandler   stage3a = new JournalPublishHandler(journalPub);
        final ExecutionReportHandler  stage3b = new ExecutionReportHandler(execPub);
        final MarketDataPublishHandler stage3c = new MarketDataPublishHandler(mktPub);

        disruptor = new com.lmax.disruptor.dsl.Disruptor<>(
            MatchingEvent.FACTORY, 1024,
            r -> new Thread(r, "test-disruptor"),
            com.lmax.disruptor.dsl.ProducerType.SINGLE,
            new com.lmax.disruptor.BusySpinWaitStrategy()
        );
        disruptor.handleEventsWith(stage1).then(stage2).then(stage3a, stage3b, stage3c);
        ringBuffer = disruptor.start();
    }

    @AfterEach
    void tearDown() {
        disruptor.shutdown();
    }

    private OrderNode makeNode(final long orderId, final byte side,
                               final long price, final long qty) {
        final OrderNode n = pool.borrow();
        assertNotNull(n);
        n.init(orderId, 1001L, SYMBOL_ID, side,
               OrderType.LIMIT.value(), TimeInForce.GTC.value(),
               price, qty, 0L, System.nanoTime());
        return n;
    }

    @Test
    @DisplayName("Pipeline: 卖单挂单 + 买单触发撮合，Stage3 回调触发")
    void pipelineShouldTriggerStage3OnMatch() throws InterruptedException {
        final AtomicInteger mktCallCount = new AtomicInteger(0);
        when(mktPub.offer(any(DirectBuffer.class), anyInt(), anyInt()))
            .thenAnswer(inv -> { mktCallCount.incrementAndGet(); return 1L; });

        // Step 1: 挂卖单（不会成交，Stage3 无行情推送）
        final OrderNode sell = makeNode(1L, Side.SELL.value(), 5000_00L, 100L);
        ringBuffer.publishEvent((e, seq) -> {
            e.reset(); e.eventType = 1; e.orderNode = sell;
        });

        Thread.sleep(50);   // 等 Pipeline 处理
        assertEquals(0, mktCallCount.get());

        // Step 2: 买单触发撮合，应产生行情推送
        final OrderNode buy = makeNode(2L, Side.BUY.value(), 5000_00L, 100L);
        ringBuffer.publishEvent((e, seq) -> {
            e.reset(); e.eventType = 1; e.orderNode = buy;
        });

        Thread.sleep(100);
        assertTrue(mktCallCount.get() >= 1, "MarketData should have been published");

        // Journal 和 ExecReport 也应被调用
        verify(journalPub, atLeastOnce()).offer(any(DirectBuffer.class), anyInt(), anyInt());
        verify(execPub,    atLeastOnce()).offer(any(DirectBuffer.class), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Pipeline: 撤单事件正确路由到 MatchingHandler")
    void pipelineShouldRouteCancelEventCorrectly() throws InterruptedException {
        // 先挂一张卖单
        final OrderNode sell = makeNode(1L, Side.SELL.value(), 5000_00L, 100L);
        ringBuffer.publishEvent((e, seq) -> {
            e.reset(); e.eventType = 1; e.orderNode = sell;
        });
        Thread.sleep(50);

        // 发撤单事件
        final CountDownLatch latch = new CountDownLatch(1);
        when(execPub.offer(any(DirectBuffer.class), anyInt(), anyInt()))
            .thenAnswer(inv -> { latch.countDown(); return 1L; });

        ringBuffer.publishEvent((e, seq) -> {
            e.reset(); e.eventType = 2; e.cancelOrderId = 1L;
        });

        // 验证订单簿已清空（撤单后）
        Thread.sleep(100);
        assertTrue(book.isAskEmpty(), "Ask side should be empty after cancel");
    }
}
```

### 9.1 运行集成冒烟测试

先添加 Mockito 依赖到 `matching-engine/pom.xml`：

```xml
<!-- 测试 Mock -->
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <version>5.11.0</version>
    <scope>test</scope>
</dependency>
```

```bash
cd trading-platform
mvn test -pl matching-engine -Dtest=MatchingPipelineSmokeTest -Dcheckstyle.skip=true
# 期望：Tests run: 2, Failures: 0, Errors: 0
```

---

## Part 3 完成检查清单

- [ ] `MatchingEvent` 预分配 `MatchResult`，reset 字段无遗漏
- [ ] `SequenceAssignHandler` 序列号严格单调递增
- [ ] `MatchingHandler` 正确路由 NewOrder / CancelOrder 两类事件
- [ ] Stage 3 三个 Handler 独立运行（任一 Handler 异常不影响其他）
- [ ] `InboundOrderSubscriber` SBE 解码后字段映射到 `OrderNode` 无遗漏
- [ ] `MatchingDisruptor` Pipeline 拓扑：Stage1 → Stage2 → [Stage3a ‖ Stage3b ‖ Stage3c]
- [ ] `MatchingPipelineSmokeTest` 2 个测试通过

---

## 下一步：Part 4

Part 3 完成后，进入 **Part 4：全场景单元测试**，覆盖：

1. 空簿撮合（买盘/卖盘为空时各订单类型的行为）
2. 多档位扫穿（市价单/大额限价单跨多个价格档位成交）
3. 同价位多单时间优先（链表顺序验证）
4. FOK 预检边界（恰好能成交 / 差 1 单位不足）
5. GTD 订单过期处理
6. 对象池耗尽降级处理
7. 手续费计算精度验证
