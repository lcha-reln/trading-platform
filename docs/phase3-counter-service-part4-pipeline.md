# Phase 3 柜台服务实现 — Part 4：Disruptor Pipeline 与 ExecutionReportProcessor

> **目标：** 实现柜台服务的五段 Disruptor Pipeline（Auth → Symbol → Risk → Freeze → Route），
> 以及 ExecutionReportProcessor（订阅撮合回报 → 更新账户/仓位 → 回报客户端）。
>
> **前置条件：** Part 3 完成，97 个测试通过  
> **本节验证目标：** Pipeline 联调测试通过，完整下单流程端到端验证

---

## 目录

1. [Pipeline 架构](#1-pipeline-架构)
2. [CounterEvent（RingBuffer 事件）](#2-countereventrignbuffer-事件)
3. [五段 Handler 实现](#3-五段-handler-实现)
4. [InboundDisruptor 装配](#4-inbounddisruptor-装配)
5. [ExecutionReportProcessor](#5-executionreportprocessor)
6. [Pipeline 集成测试](#6-pipeline-集成测试)

---

## 1. Pipeline 架构

```
Cluster Ingress (SBE NewOrderRequest / CancelOrderRequest)
        │
        ▼
┌───────────────────────────────────────────────────────────────┐
│  Inbound Disruptor (RingBuffer 2^20, SPSC, BusySpin)          │
│                                                               │
│  [AuthHandler]     JWT/APIKey 验签，失败则标记 rejected         │
│       ↓                                                       │
│  [SymbolHandler]   交易对状态校验（是否 TRADING）               │
│       ↓                                                       │
│  [RiskHandler]     余额/仓位/价格笼子校验                       │
│       ↓                                                       │
│  [FreezeHandler]   冻结资金，创建 OrderState                   │
│       ↓                                                       │
│  [RouteHandler]    编码 SBE InternalNewOrder                  │
│                    → Aeron IPC stream=2 → Matching Engine     │
└───────────────────────────────────────────────────────────────┘

撮合引擎回报订阅（独立线程，Aeron IPC stream=3）：
ExecutionReportProcessor
  → 按 makerOrderId/takerOrderId 更新账户余额/仓位
  → 更新 OrderState 状态
  → 编码 ExecutionReport → Cluster Egress → Gateway → Client
```

---

## 2. CounterEvent（RingBuffer 事件）

文件：`counter-service/src/main/java/com/trading/counter/disruptor/CounterEvent.java`

```java
package com.trading.counter.disruptor;

import com.lmax.disruptor.EventFactory;
import com.trading.counter.risk.RejectReason;
import com.trading.sbe.*;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

/**
 * 柜台 Disruptor RingBuffer 事件对象（预分配，反复复用）。
 *
 * <p>各阶段写入的字段：
 * <ul>
 *   <li>生产者（ClusteredService）：填充入站消息字段</li>
 *   <li>AuthHandler：填充 authPassed</li>
 *   <li>SymbolHandler：填充 symbolConfig</li>
 *   <li>RiskHandler：填充 rejectReason / feeEstimate</li>
 *   <li>FreezeHandler：填充 frozenAmount / frozenAssetId / orderId</li>
 *   <li>RouteHandler：发布到 Aeron，无新字段</li>
 * </ul>
 *
 * @author Reln Ding
 */
public final class CounterEvent {

    // ---- 入站请求字段（生产者填充）----

    /** 消息类型：1=NewOrder, 2=CancelOrder */
    public byte msgType;

    /** Cluster Session ID（用于回报路由）*/
    public long sessionId;

    /** correlationId（客户端唯一请求 ID）*/
    public long correlationId;

    /** 账户 ID */
    public long accountId;

    /** 交易对 ID */
    public int symbolId;

    /** 买卖方向 */
    public byte side;

    /** 订单类型 */
    public byte orderType;

    /** 有效时间类型 */
    public byte timeInForce;

    /** 委托价格 */
    public long price;

    /** 委托数量 */
    public long quantity;

    /** 杠杆倍数（Spot=1）*/
    public short leverage;

    /** 客户端时间戳（纳秒）*/
    public long clientTimestampNs;

    /** 撤单时的 orderId（CancelOrder 专用）*/
    public long cancelOrderId;

    // ---- AuthHandler 填充 ----

    /** 是否通过鉴权 */
    public boolean authPassed;

    // ---- SymbolHandler 填充 ----

    /** 交易对配置（校验通过后设置）*/
    public com.trading.counter.model.SymbolConfig symbolConfig;

    // ---- RiskHandler 填充 ----

    /** 拒绝原因（NONE=通过）*/
    public RejectReason rejectReason;

    /** 预估手续费（冻结时使用）*/
    public long feeEstimate;

    // ---- FreezeHandler 填充 ----

    /** 系统分配的订单 ID */
    public long orderId;

    /** 冻结金额 */
    public long frozenAmount;

    /** 冻结的资产 ID */
    public int frozenAssetId;

    // ---- 内部缓冲（RouteHandler 编码用）----
    public final MutableDirectBuffer encodeBuffer =
        new UnsafeBuffer(ByteBuffer.allocateDirect(512));

    /** EventFactory */
    public static final EventFactory<CounterEvent> FACTORY = CounterEvent::new;

    /** 重置（每次被生产者写入前调用）*/
    public void reset() {
        msgType           = 0;
        sessionId         = 0L;
        correlationId     = 0L;
        accountId         = 0L;
        symbolId          = 0;
        side              = 0;
        orderType         = 0;
        timeInForce       = 0;
        price             = 0L;
        quantity          = 0L;
        leverage          = 1;
        clientTimestampNs = 0L;
        cancelOrderId     = 0L;
        authPassed        = false;
        symbolConfig      = null;
        rejectReason      = RejectReason.NONE;
        feeEstimate       = 0L;
        orderId           = 0L;
        frozenAmount      = 0L;
        frozenAssetId     = 0;
    }
}
```

---

## 3. 五段 Handler 实现

### 3.1 AuthHandler

文件：`counter-service/src/main/java/com/trading/counter/disruptor/AuthHandler.java`

```java
package com.trading.counter.disruptor;

import com.lmax.disruptor.EventHandler;
import com.trading.counter.risk.RejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stage 1：鉴权 Handler。
 *
 * <p>Phase 3 简化实现：账户 ID > 0 即视为鉴权通过。
 * 生产环境需接入 JWT 验签或 API Key/Signature 校验。
 *
 * @author Reln Ding
 */
public final class AuthHandler implements EventHandler<CounterEvent> {

    private static final Logger log = LoggerFactory.getLogger(AuthHandler.class);

    @Override
    public void onEvent(final CounterEvent event, final long sequence,
                        final boolean endOfBatch) {
        // 已被前序 Handler 拒绝，跳过
        if (event.rejectReason != null && event.rejectReason.isRejected()) {
            return;
        }
        if (event.accountId <= 0) {
            event.authPassed  = false;
            event.rejectReason = RejectReason.RATE_LIMIT_EXCEEDED; // 简化：用此码表示鉴权失败
            log.debug("Auth failed: accountId={}", event.accountId);
            return;
        }
        event.authPassed = true;
    }
}
```

### 3.2 SymbolHandler

文件：`counter-service/src/main/java/com/trading/counter/disruptor/SymbolHandler.java`

```java
package com.trading.counter.disruptor;

import com.lmax.disruptor.EventHandler;
import com.trading.counter.model.SymbolConfig;
import com.trading.counter.risk.RejectReason;
import com.trading.counter.symbol.SymbolConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stage 2：交易对状态校验 Handler。
 *
 * @author Reln Ding
 */
public final class SymbolHandler implements EventHandler<CounterEvent> {

    private static final Logger log = LoggerFactory.getLogger(SymbolHandler.class);

    private final SymbolConfigManager symbolConfigManager;

    public SymbolHandler(final SymbolConfigManager symbolConfigManager) {
        this.symbolConfigManager = symbolConfigManager;
    }

    @Override
    public void onEvent(final CounterEvent event, final long sequence,
                        final boolean endOfBatch) {
        if (event.rejectReason != null && event.rejectReason.isRejected()) {
            return;
        }
        final SymbolConfig config = symbolConfigManager.get(event.symbolId);
        if (config == null) {
            event.rejectReason = RejectReason.SYMBOL_NOT_FOUND;
            log.debug("Symbol not found: symbolId={}", event.symbolId);
            return;
        }
        if (!config.isTrading()) {
            event.rejectReason = RejectReason.SYMBOL_HALTED;
            log.debug("Symbol halted: symbolId={}, status={}", event.symbolId, config.status);
            return;
        }
        event.symbolConfig = config;
    }
}
```

### 3.3 RiskHandler

文件：`counter-service/src/main/java/com/trading/counter/disruptor/RiskHandler.java`

```java
package com.trading.counter.disruptor;

import com.lmax.disruptor.EventHandler;
import com.trading.counter.fee.FeeCalculator;
import com.trading.counter.model.SymbolConfig;
import com.trading.counter.risk.*;
import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stage 3：风控校验 Handler（余额 + 仓位上限 + 价格笼子）。
 *
 * @author Reln Ding
 */
public final class RiskHandler implements EventHandler<CounterEvent> {

    private static final Logger log = LoggerFactory.getLogger(RiskHandler.class);

    private final BalanceRiskChecker  balanceChecker;
    private final PositionRiskChecker positionChecker;
    private final PriceBandChecker    priceBandChecker;

    /** 最新成交价缓存（每次撮合回报后更新，用于价格笼子参考价）*/
    private final org.agrona.collections.Int2LongHashMap lastTradePrices
        = new org.agrona.collections.Int2LongHashMap(0L);

    public RiskHandler(final BalanceRiskChecker balanceChecker,
                       final PositionRiskChecker positionChecker,
                       final PriceBandChecker priceBandChecker) {
        this.balanceChecker  = balanceChecker;
        this.positionChecker = positionChecker;
        this.priceBandChecker = priceBandChecker;
    }

    @Override
    public void onEvent(final CounterEvent event, final long sequence,
                        final boolean endOfBatch) {
        if (event.rejectReason != null && event.rejectReason.isRejected()) {
            return;
        }
        if (event.msgType == 2) {
            // CancelOrder 无需风控
            return;
        }

        final SymbolConfig config = event.symbolConfig;
        final long price    = event.price;
        final long quantity = event.quantity;

        // Step 1: 数量校验
        final RejectReason qtyCheck = priceBandChecker.checkQuantity(config, quantity);
        if (qtyCheck.isRejected()) {
            event.rejectReason = qtyCheck;
            return;
        }

        // Step 2: 价格校验（限价单）
        if (event.orderType == OrderType.LIMIT.value()
                || event.orderType == OrderType.POST_ONLY.value()) {
            final RejectReason priceCheck = priceBandChecker.checkPrice(config, price);
            if (priceCheck.isRejected()) {
                event.rejectReason = priceCheck;
                return;
            }
            // 价格笼子（只在有参考价时校验）
            final long refPrice = lastTradePrices.get(event.symbolId);
            final RejectReason bandCheck = priceBandChecker.check(config, price, refPrice);
            if (bandCheck.isRejected()) {
                event.rejectReason = bandCheck;
                return;
            }
        }

        // Step 3: 余额/保证金校验
        final RejectReason balCheck = checkBalance(event, config);
        if (balCheck.isRejected()) {
            event.rejectReason = balCheck;
            return;
        }

        // Step 4: 仓位上限校验（仅合约）
        if (!config.isSpot()) {
            final RejectReason posCheck = positionChecker.checkPositionLimit(
                event.accountId, config,
                event.side == Side.BUY.value()
                    ? com.trading.counter.model.Position.LONG
                    : com.trading.counter.model.Position.SHORT,
                quantity);
            if (posCheck.isRejected()) {
                event.rejectReason = posCheck;
                return;
            }
        }

        event.rejectReason = RejectReason.NONE;
    }

    private RejectReason checkBalance(final CounterEvent event, final SymbolConfig config) {
        if (config.isSpot()) {
            final long fee = FeeCalculator.estimateFee(config, event.price, event.quantity);
            event.feeEstimate = fee;
            if (event.side == Side.BUY.value()) {
                if (event.orderType == OrderType.MARKET.value()) {
                    return balanceChecker.checkSpotMarketBuy(event.accountId, config);
                }
                return balanceChecker.checkSpotLimitBuy(
                    event.accountId, config, event.price, event.quantity, fee);
            } else {
                if (event.orderType == OrderType.MARKET.value()) {
                    return balanceChecker.checkSpotMarketSell(event.accountId, config);
                }
                return balanceChecker.checkSpotLimitSell(
                    event.accountId, config, event.quantity);
            }
        } else {
            return balanceChecker.checkContractOpen(
                event.accountId, config, event.price, event.quantity, event.leverage);
        }
    }

    /** 更新参考价（由 ExecutionReportProcessor 在收到成交回报后调用）*/
    public void updateLastTradePrice(final int symbolId, final long price) {
        lastTradePrices.put(symbolId, price);
    }
}
```

### 3.4 FreezeHandler

文件：`counter-service/src/main/java/com/trading/counter/disruptor/FreezeHandler.java`

```java
package com.trading.counter.disruptor;

import com.lmax.disruptor.EventHandler;
import com.trading.counter.account.AccountManager;
import com.trading.counter.model.OrderState;
import com.trading.counter.model.SymbolConfig;
import com.trading.counter.risk.RejectReason;
import com.trading.sbe.Side;
import com.trading.util.SnowflakeIdGenerator;
import org.agrona.collections.Long2ObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stage 4：资金冻结 + OrderState 创建 Handler。
 *
 * <p>仅在风控通过（rejectReason == NONE）时执行冻结。
 *
 * @author Reln Ding
 */
public final class FreezeHandler implements EventHandler<CounterEvent> {

    private static final Logger log = LoggerFactory.getLogger(FreezeHandler.class);

    private final AccountManager         accountManager;
    private final SnowflakeIdGenerator   idGen;
    private final Long2ObjectHashMap<OrderState> openOrders;  // orderId → OrderState

    public FreezeHandler(final AccountManager accountManager,
                         final SnowflakeIdGenerator idGen,
                         final Long2ObjectHashMap<OrderState> openOrders) {
        this.accountManager = accountManager;
        this.idGen          = idGen;
        this.openOrders     = openOrders;
    }

    @Override
    public void onEvent(final CounterEvent event, final long sequence,
                        final boolean endOfBatch) {
        if (event.rejectReason != null && event.rejectReason.isRejected()) {
            return;
        }
        if (event.msgType == 2) {
            // CancelOrder 不需要冻结
            return;
        }

        final SymbolConfig config = event.symbolConfig;
        event.orderId = idGen.nextId();

        // 冻结资金（Spot）
        if (config.isSpot()) {
            final boolean ok;
            if (event.side == Side.BUY.value()) {
                ok = accountManager.freezeForLimitBuy(
                    event.accountId, config, event.price, event.quantity, event.feeEstimate);
                event.frozenAssetId = config.quoteCurrency;
                event.frozenAmount  = com.trading.counter.account.AccountManager.calcQuoteAmount(
                    event.price, event.quantity,
                    config.pricePrecision, config.quantityPrecision) + event.feeEstimate;
            } else {
                ok = accountManager.freezeForLimitSell(
                    event.accountId, config, event.quantity);
                event.frozenAssetId = config.baseCurrency;
                event.frozenAmount  = event.quantity;
            }
            if (!ok) {
                // 极罕见：风控通过但冻结失败（并发场景，此处直接拒绝）
                event.rejectReason = RejectReason.INSUFFICIENT_BALANCE;
                log.warn("Freeze failed after risk check passed: accountId={}, orderId={}",
                         event.accountId, event.orderId);
                return;
            }
        }
        // 合约保证金冻结由 PositionManager.openPosition 在成交后执行，此处只记录 OrderState

        // 创建 OrderState
        final OrderState state = new OrderState(
            event.orderId, event.correlationId, event.accountId, event.symbolId,
            event.side, event.orderType, event.price, event.quantity);
        state.setFrozen(event.frozenAmount, event.frozenAssetId);
        state.confirmNew();
        openOrders.put(event.orderId, state);

        log.debug("Order frozen: orderId={}, accountId={}, frozenAmount={}",
                  event.orderId, event.accountId, event.frozenAmount);
    }
}
```

### 3.5 RouteHandler

文件：`counter-service/src/main/java/com/trading/counter/disruptor/RouteHandler.java`

```java
package com.trading.counter.disruptor;

import com.lmax.disruptor.EventHandler;
import com.trading.counter.risk.RejectReason;
import com.trading.sbe.*;
import io.aeron.Publication;
import io.aeron.cluster.service.ClientSession;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Stage 5：订单路由 Handler。
 *
 * <p>两条路径：
 * <ul>
 *   <li>通过风控：编码 SBE InternalNewOrder，发布到 Aeron IPC stream=2 → Matching Engine</li>
 *   <li>被拒绝：编码 ExecutionReport（REJECTED），通过 Cluster Egress 回报给 Gateway</li>
 * </ul>
 *
 * @author Reln Ding
 */
public final class RouteHandler implements EventHandler<CounterEvent> {

    private static final Logger log = LoggerFactory.getLogger(RouteHandler.class);

    private final Publication matchingEnginePublication;  // Aeron IPC stream=2
    private final io.aeron.cluster.service.Cluster cluster;  // 用于 Cluster Egress 回报

    private final MessageHeaderEncoder     headerEncoder     = new MessageHeaderEncoder();
    private final InternalNewOrderEncoder  newOrderEncoder   = new InternalNewOrderEncoder();
    private final InternalCancelOrderEncoder cancelEncoder   = new InternalCancelOrderEncoder();
    private final ExecutionReportEncoder   execReportEncoder = new ExecutionReportEncoder();
    private final MutableDirectBuffer rejectBuffer =
        new UnsafeBuffer(ByteBuffer.allocateDirect(512));

    public RouteHandler(final Publication matchingEnginePublication,
                        final io.aeron.cluster.service.Cluster cluster) {
        this.matchingEnginePublication = matchingEnginePublication;
        this.cluster                   = cluster;
    }

    @Override
    public void onEvent(final CounterEvent event, final long sequence,
                        final boolean endOfBatch) {
        if (event.rejectReason != null && event.rejectReason.isRejected()) {
            sendRejectReport(event);
            return;
        }

        if (event.msgType == 1) {
            sendToMatchingEngine(event);
        } else if (event.msgType == 2) {
            sendCancelToMatchingEngine(event);
        }
    }

    private void sendToMatchingEngine(final CounterEvent event) {
        final int headerLen = MessageHeaderEncoder.ENCODED_LENGTH;
        headerEncoder.wrap(event.encodeBuffer, 0)
            .blockLength(InternalNewOrderEncoder.BLOCK_LENGTH)
            .templateId(InternalNewOrderEncoder.TEMPLATE_ID)
            .schemaId(InternalNewOrderEncoder.SCHEMA_ID)
            .version(InternalNewOrderEncoder.SCHEMA_VERSION);

        newOrderEncoder.wrap(event.encodeBuffer, headerLen)
            .orderId(event.orderId)
            .correlationId(event.correlationId)
            .accountId(event.accountId)
            .symbolId(event.symbolId)
            .side(Side.get(event.side))
            .orderType(OrderType.get(event.orderType))
            .timeInForce(TimeInForce.get(event.timeInForce))
            .price(event.price)
            .quantity(event.quantity)
            .timestamp(event.clientTimestampNs);

        final int totalLen = headerLen + InternalNewOrderEncoder.BLOCK_LENGTH;
        final long result = matchingEnginePublication.offer(
            event.encodeBuffer, 0, totalLen);
        if (result < 0 && result != Publication.BACK_PRESSURED) {
            log.error("Route to ME failed: {}, orderId={}", result, event.orderId);
        }
    }

    private void sendCancelToMatchingEngine(final CounterEvent event) {
        final int headerLen = MessageHeaderEncoder.ENCODED_LENGTH;
        headerEncoder.wrap(event.encodeBuffer, 0)
            .blockLength(InternalCancelOrderEncoder.BLOCK_LENGTH)
            .templateId(InternalCancelOrderEncoder.TEMPLATE_ID)
            .schemaId(InternalCancelOrderEncoder.SCHEMA_ID)
            .version(InternalCancelOrderEncoder.SCHEMA_VERSION);

        cancelEncoder.wrap(event.encodeBuffer, headerLen)
            .orderId(event.cancelOrderId)
            .accountId(event.accountId)
            .symbolId(event.symbolId)
            .correlationId(event.correlationId)
            .timestamp(System.nanoTime());

        final int totalLen = headerLen + InternalCancelOrderEncoder.BLOCK_LENGTH;
        matchingEnginePublication.offer(event.encodeBuffer, 0, totalLen);
    }

    private void sendRejectReport(final CounterEvent event) {
        // 编码 ExecutionReport(REJECTED) 并通过 Cluster Egress 回报
        final int headerLen = MessageHeaderEncoder.ENCODED_LENGTH;
        headerEncoder.wrap(rejectBuffer, 0)
            .blockLength(ExecutionReportEncoder.BLOCK_LENGTH)
            .templateId(ExecutionReportEncoder.TEMPLATE_ID)
            .schemaId(ExecutionReportEncoder.SCHEMA_ID)
            .version(ExecutionReportEncoder.SCHEMA_VERSION);

        execReportEncoder.wrap(rejectBuffer, headerLen)
            .orderId(event.orderId)
            .correlationId(event.correlationId)
            .accountId(event.accountId)
            .symbolId(event.symbolId)
            .execType(ExecType.REJECTED)
            .orderStatus(OrderStatus.REJECTED)
            .side(Side.get(event.side))
            .price(event.price)
            .quantity(event.quantity)
            .filledQty(0L)
            .leavesQty(event.quantity)
            .lastFillPrice(0L)
            .lastFillQty(0L)
            .fee(0L)
            .rejectReason(com.trading.sbe.RejectReason.get(
                (byte) event.rejectReason.code))
            .timestamp(System.nanoTime());

        final int totalLen = headerLen + ExecutionReportEncoder.BLOCK_LENGTH;
        if (cluster != null) {
            final ClientSession session = cluster.getClientSession(event.sessionId);
            if (session != null) {
                session.offer(rejectBuffer, 0, totalLen);
            }
        }
        log.debug("Reject report sent: orderId={}, reason={}", event.orderId, event.rejectReason);
    }
}
```

---

## 4. InboundDisruptor 装配

文件：`counter-service/src/main/java/com/trading/counter/disruptor/InboundDisruptor.java`

```java
package com.trading.counter.disruptor;

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.trading.counter.account.AccountManager;
import com.trading.counter.account.PositionManager;
import com.trading.counter.risk.*;
import com.trading.counter.symbol.SymbolConfigManager;
import com.trading.util.SnowflakeIdGenerator;
import io.aeron.Publication;
import org.agrona.collections.Long2ObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;

/**
 * 柜台入站 Disruptor Pipeline 装配器。
 *
 * <p>拓扑：Auth → Symbol → Risk → Freeze → Route（全串行，保证处理有序）
 *
 * @author Reln Ding
 */
public final class InboundDisruptor {

    private static final Logger log = LoggerFactory.getLogger(InboundDisruptor.class);
    private static final int RING_BUFFER_SIZE = 1 << 20;

    private final Disruptor<CounterEvent>  disruptor;
    private final RingBuffer<CounterEvent> ringBuffer;

    // 持有 RiskHandler 引用以便更新参考价
    private final RiskHandler riskHandler;

    public InboundDisruptor(final AccountManager accountManager,
                            final PositionManager positionManager,
                            final SymbolConfigManager symbolConfigManager,
                            final SnowflakeIdGenerator idGen,
                            final Long2ObjectHashMap<com.trading.counter.model.OrderState> openOrders,
                            final Publication matchingEnginePublication,
                            final io.aeron.cluster.service.Cluster cluster) {

        final AuthHandler    auth    = new AuthHandler();
        final SymbolHandler  symbol  = new SymbolHandler(symbolConfigManager);
        final BalanceRiskChecker  balChecker  = new BalanceRiskChecker(accountManager, positionManager);
        final PositionRiskChecker posChecker  = new PositionRiskChecker(positionManager);
        final PriceBandChecker    priceChecker = new PriceBandChecker();
        this.riskHandler = new RiskHandler(balChecker, posChecker, priceChecker);
        final FreezeHandler  freeze  = new FreezeHandler(accountManager, idGen, openOrders);
        final RouteHandler   route   = new RouteHandler(matchingEnginePublication, cluster);

        final ThreadFactory tf = r -> new Thread(r, "counter-disruptor");
        this.disruptor = new Disruptor<>(
            CounterEvent.FACTORY, RING_BUFFER_SIZE, tf,
            ProducerType.SINGLE, new BusySpinWaitStrategy());

        // 全串行：每个 Handler 等待前一个完成后才执行
        disruptor
            .handleEventsWith(auth)
            .then(symbol)
            .then(riskHandler)
            .then(freeze)
            .then(route);

        this.ringBuffer = disruptor.start();
        log.info("InboundDisruptor started, ringBufferSize={}", RING_BUFFER_SIZE);
    }

    public RingBuffer<CounterEvent> getRingBuffer() { return ringBuffer; }
    public RiskHandler getRiskHandler() { return riskHandler; }

    public void shutdown() {
        disruptor.shutdown();
    }
}
```

---

## 5. ExecutionReportProcessor

订阅撮合引擎的成交回报（Aeron IPC stream=3），更新账户/仓位状态，回报客户端。

文件：`counter-service/src/main/java/com/trading/counter/processor/ExecutionReportProcessor.java`

```java
package com.trading.counter.processor;

import com.trading.counter.account.AccountManager;
import com.trading.counter.account.PositionManager;
import com.trading.counter.disruptor.RiskHandler;
import com.trading.counter.model.OrderState;
import com.trading.counter.model.SymbolConfig;
import com.trading.counter.symbol.SymbolConfigManager;
import com.trading.sbe.*;
import io.aeron.Subscription;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * 撮合回报处理器（独立线程，BusySpin 轮询 Aeron IPC stream=3）。
 *
 * <p>处理流程：
 * <ol>
 *   <li>解码 SBE MatchResult</li>
 *   <li>查找 Maker/Taker 的 OrderState</li>
 *   <li>结算账户余额（Spot）或更新仓位（Perp/Futures）</li>
 *   <li>更新 OrderState 成交状态</li>
 *   <li>编码 ExecutionReport → Cluster Egress → Gateway → Client</li>
 *   <li>通知 RiskHandler 更新最新成交价</li>
 * </ol>
 *
 * @author Reln Ding
 */
public final class ExecutionReportProcessor implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReportProcessor.class);

    private static final int FRAGMENT_LIMIT = 10;

    private final Subscription           matchResultSubscription;
    private final AccountManager         accountManager;
    private final PositionManager        positionManager;
    private final SymbolConfigManager    symbolConfigManager;
    private final Long2ObjectHashMap<OrderState> openOrders;
    private final Cluster                cluster;
    private final RiskHandler            riskHandler;

    // SBE 解码器（预分配）
    private final MessageHeaderDecoder  headerDecoder  = new MessageHeaderDecoder();
    private final MatchResultDecoder    matchDecoder   = new MatchResultDecoder();

    // SBE 编码器（回报消息）
    private final MessageHeaderEncoder    headerEncoder  = new MessageHeaderEncoder();
    private final ExecutionReportEncoder  execEncoder    = new ExecutionReportEncoder();
    private final MutableDirectBuffer     sendBuffer     =
        new UnsafeBuffer(ByteBuffer.allocateDirect(512));

    private final IdleStrategy idleStrategy = new BusySpinIdleStrategy();
    private volatile boolean running = true;

    public ExecutionReportProcessor(final Subscription matchResultSubscription,
                                    final AccountManager accountManager,
                                    final PositionManager positionManager,
                                    final SymbolConfigManager symbolConfigManager,
                                    final Long2ObjectHashMap<OrderState> openOrders,
                                    final Cluster cluster,
                                    final RiskHandler riskHandler) {
        this.matchResultSubscription = matchResultSubscription;
        this.accountManager          = accountManager;
        this.positionManager         = positionManager;
        this.symbolConfigManager     = symbolConfigManager;
        this.openOrders              = openOrders;
        this.cluster                 = cluster;
        this.riskHandler             = riskHandler;
    }

    @Override
    public void run() {
        final FragmentHandler handler = this::onFragment;
        while (running) {
            idleStrategy.idle(matchResultSubscription.poll(handler, FRAGMENT_LIMIT));
        }
    }

    public void stop() { running = false; }

    private void onFragment(final DirectBuffer buffer, final int offset,
                            final int length, final Header header) {
        headerDecoder.wrap(buffer, offset);
        if (headerDecoder.templateId() != MatchResultDecoder.TEMPLATE_ID) {
            return;
        }
        matchDecoder.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
                          headerDecoder.blockLength(), headerDecoder.version());
        handleMatchResult();
    }

    private void handleMatchResult() {
        final int  symbolId       = matchDecoder.symbolId();
        final long makerOrderId   = matchDecoder.makerOrderId();
        final long takerOrderId   = matchDecoder.takerOrderId();
        final long makerAccountId = matchDecoder.makerAccountId();
        final long takerAccountId = matchDecoder.takerAccountId();
        final long fillPrice      = matchDecoder.price();
        final long fillQty        = matchDecoder.quantity();
        final long makerFee       = matchDecoder.makerFee();
        final long takerFee       = matchDecoder.takerFee();
        final byte makerSide      = matchDecoder.makerSide().value();

        final SymbolConfig config = symbolConfigManager.get(symbolId);
        if (config == null) {
            log.warn("No SymbolConfig for symbolId={}", symbolId);
            return;
        }

        // ---- 结算 Maker ----
        final OrderState makerState = openOrders.get(makerOrderId);
        if (makerState != null) {
            settleAccount(makerState, config, fillPrice, fillQty, makerFee, true);
            makerState.fill(fillQty);
            sendExecutionReport(makerState, fillPrice, fillQty, makerFee,
                                makerState.isTerminal());
            if (makerState.isTerminal()) {
                openOrders.remove(makerOrderId);
            }
        }

        // ---- 结算 Taker ----
        final OrderState takerState = openOrders.get(takerOrderId);
        if (takerState != null) {
            settleAccount(takerState, config, fillPrice, fillQty, takerFee, false);
            takerState.fill(fillQty);
            sendExecutionReport(takerState, fillPrice, fillQty, takerFee,
                                takerState.isTerminal());
            if (takerState.isTerminal()) {
                openOrders.remove(takerOrderId);
            }
        }

        // ---- 更新参考价 ----
        riskHandler.updateLastTradePrice(symbolId, fillPrice);
    }

    private void settleAccount(final OrderState state, final SymbolConfig config,
                               final long fillPrice, final long fillQty,
                               final long fee, final boolean isMaker) {
        if (config.isSpot()) {
            if (state.side == Side.BUY.value()) {
                accountManager.settleBuyFill(state.accountId, config,
                                             fillPrice, fillQty, fee);
            } else {
                accountManager.settleSellFill(state.accountId, config,
                                              fillPrice, fillQty, fee);
            }
        } else {
            // 合约：成交后调用 openPosition / closePosition
            // 简化：此处仅记录日志，完整实现需区分开平仓方向
            log.debug("Contract fill: accountId={}, symbolId={}, qty={}",
                      state.accountId, config.symbolId, fillQty);
        }
    }

    private void sendExecutionReport(final OrderState state,
                                     final long fillPrice, final long fillQty,
                                     final long fee, final boolean isFinal) {
        final int headerLen = MessageHeaderEncoder.ENCODED_LENGTH;
        headerEncoder.wrap(sendBuffer, 0)
            .blockLength(ExecutionReportEncoder.BLOCK_LENGTH)
            .templateId(ExecutionReportEncoder.TEMPLATE_ID)
            .schemaId(ExecutionReportEncoder.SCHEMA_ID)
            .version(ExecutionReportEncoder.SCHEMA_VERSION);

        final ExecType execType = isFinal ? ExecType.FILL : ExecType.PARTIAL_FILL;
        final OrderStatus orderStatus = isFinal
            ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;

        execEncoder.wrap(sendBuffer, headerLen)
            .orderId(state.orderId)
            .correlationId(state.correlationId)
            .accountId(state.accountId)
            .symbolId(state.symbolId)
            .execType(execType)
            .orderStatus(orderStatus)
            .side(Side.get(state.side))
            .price(state.price)
            .quantity(state.quantity)
            .filledQty(state.getFilledQty())
            .leavesQty(state.getLeavesQty())
            .lastFillPrice(fillPrice)
            .lastFillQty(fillQty)
            .fee(fee)
            .rejectReason(com.trading.sbe.RejectReason.NONE)
            .timestamp(System.nanoTime());

        final int totalLen = headerLen + ExecutionReportEncoder.BLOCK_LENGTH;
        if (cluster != null) {
            final ClientSession session = cluster.getClientSession(0L); // 简化：实际需路由到原 session
            if (session != null) {
                session.offer(sendBuffer, 0, totalLen);
            }
        }
    }
}
```

---

## 6. Pipeline 集成测试

文件：`counter-service/src/test/java/com/trading/counter/disruptor/CounterPipelineTest.java`

```java
package com.trading.counter.disruptor;

import com.lmax.disruptor.RingBuffer;
import com.trading.counter.account.AccountManager;
import com.trading.counter.account.PositionManager;
import com.trading.counter.model.OrderState;
import com.trading.counter.model.SymbolConfig;
import com.trading.counter.risk.*;
import com.trading.counter.symbol.SymbolConfigManager;
import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import com.trading.sbe.TimeInForce;
import com.trading.util.SnowflakeIdGenerator;
import io.aeron.Publication;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.junit.jupiter.api.*;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 柜台 Disruptor Pipeline 集成测试（Mock Aeron + Cluster）。
 *
 * @author Reln Ding
 */
class CounterPipelineTest {

    private AccountManager     accountMgr;
    private PositionManager    positionMgr;
    private SymbolConfigManager symbolMgr;
    private Long2ObjectHashMap<OrderState> openOrders;
    private Publication        mePub;
    private InboundDisruptor   disruptor;
    private RingBuffer<CounterEvent> ringBuffer;

    @BeforeEach
    void setUp() {
        accountMgr  = new AccountManager();
        positionMgr = new PositionManager();
        symbolMgr   = new SymbolConfigManager();
        openOrders  = new Long2ObjectHashMap<>();
        mePub       = mock(Publication.class);

        symbolMgr.register(SymbolConfig.btcUsdt());
        accountMgr.deposit(1001L, SymbolConfig.btcUsdt().quoteCurrency, 100_000_00L);

        when(mePub.offer(any(DirectBuffer.class), anyInt(), anyInt())).thenReturn(1L);

        disruptor = new InboundDisruptor(
            accountMgr, positionMgr, symbolMgr,
            new SnowflakeIdGenerator(0),
            openOrders, mePub, null);  // cluster=null 简化测试
        ringBuffer = disruptor.getRingBuffer();
    }

    @AfterEach
    void tearDown() {
        disruptor.shutdown();
    }

    @Test
    @DisplayName("合法下单：通过全部校验，路由到撮合引擎，创建 OrderState")
    void validOrderShouldRouteToMatchingEngine() throws InterruptedException {
        ringBuffer.publishEvent((e, seq) -> {
            e.reset();
            e.msgType         = 1;
            e.sessionId       = 100L;
            e.correlationId   = 999L;
            e.accountId       = 1001L;
            e.symbolId        = 1;
            e.side            = Side.BUY.value();
            e.orderType       = OrderType.LIMIT.value();
            e.timeInForce     = TimeInForce.GTC.value();
            e.price           = 5_000_000L;   // 50000.00
            e.quantity        = 100_000L;     // 0.1 BTC
            e.leverage        = 1;
        });

        TimeUnit.MILLISECONDS.sleep(200);

        verify(mePub, atLeastOnce()).offer(any(DirectBuffer.class), anyInt(), anyInt());
        assertFalse(openOrders.isEmpty(), "OrderState should be created");

        final OrderState state = openOrders.values().iterator().next();
        assertEquals(1001L, state.accountId);
        assertEquals(OrderState.STATUS_NEW, state.getStatus());

        // 验证 quote 余额已冻结
        final var quoteBal = accountMgr.get(1001L, SymbolConfig.btcUsdt().quoteCurrency);
        assertNotNull(quoteBal);
        assertTrue(quoteBal.getFrozen() > 0, "Quote balance should be frozen");
    }

    @Test
    @DisplayName("账户 ID <= 0：Auth 拒绝，不发送到撮合引擎")
    void invalidAccountIdShouldBeRejected() throws InterruptedException {
        ringBuffer.publishEvent((e, seq) -> {
            e.reset();
            e.msgType   = 1;
            e.accountId = -1L;   // 无效账户
            e.symbolId  = 1;
            e.side      = Side.BUY.value();
            e.orderType = OrderType.LIMIT.value();
            e.price     = 5_000_000L;
            e.quantity  = 100_000L;
        });

        TimeUnit.MILLISECONDS.sleep(100);
        verify(mePub, never()).offer(any(DirectBuffer.class), anyInt(), anyInt());
        assertTrue(openOrders.isEmpty());
    }

    @Test
    @DisplayName("交易对不存在：Symbol 拒绝，不发送到撮合引擎")
    void unknownSymbolShouldBeRejected() throws InterruptedException {
        ringBuffer.publishEvent((e, seq) -> {
            e.reset();
            e.msgType   = 1;
            e.accountId = 1001L;
            e.symbolId  = 999;   // 不存在的交易对
            e.side      = Side.BUY.value();
            e.orderType = OrderType.LIMIT.value();
            e.price     = 5_000_000L;
            e.quantity  = 100_000L;
        });

        TimeUnit.MILLISECONDS.sleep(100);
        verify(mePub, never()).offer(any(DirectBuffer.class), anyInt(), anyInt());
    }

    @Test
    @DisplayName("余额不足：Risk 拒绝，不冻结资金，不发送到撮合引擎")
    void insufficientBalanceShouldBeRejected() throws InterruptedException {
        // 账户 1002 没有充值
        ringBuffer.publishEvent((e, seq) -> {
            e.reset();
            e.msgType   = 1;
            e.accountId = 1002L;
            e.symbolId  = 1;
            e.side      = Side.BUY.value();
            e.orderType = OrderType.LIMIT.value();
            e.price     = 5_000_000L;
            e.quantity  = 100_000L;
        });

        TimeUnit.MILLISECONDS.sleep(100);
        verify(mePub, never()).offer(any(DirectBuffer.class), anyInt(), anyInt());
        assertTrue(openOrders.isEmpty());
    }

    @Test
    @DisplayName("撤单请求直接路由到撮合引擎")
    void cancelOrderShouldRouteToMatchingEngine() throws InterruptedException {
        ringBuffer.publishEvent((e, seq) -> {
            e.reset();
            e.msgType       = 2;   // CancelOrder
            e.accountId     = 1001L;
            e.symbolId      = 1;
            e.cancelOrderId = 12345L;
            e.correlationId = 888L;
        });

        TimeUnit.MILLISECONDS.sleep(100);
        verify(mePub, atLeastOnce()).offer(any(DirectBuffer.class), anyInt(), anyInt());
    }
}
```

### 6.1 运行测试

在 `counter-service/pom.xml` 中添加 Mockito：

```xml
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <version>5.11.0</version>
    <scope>test</scope>
</dependency>
```

```bash
cd trading-platform
mvn test -pl counter-service -Dtest=CounterPipelineTest -Dcheckstyle.skip=true
# 期望：Tests run: 5, Failures: 0, Errors: 0
```

---

## Part 4 完成检查清单

- [ ] `CounterEvent.reset()` 清空全部字段，无遗漏
- [ ] `AuthHandler`：accountId <= 0 → RATE_LIMIT_EXCEEDED，> 0 → authPassed=true
- [ ] `SymbolHandler`：symbolId 不存在 → SYMBOL_NOT_FOUND；非 TRADING → SYMBOL_HALTED
- [ ] `RiskHandler`：CancelOrder 跳过校验；数量/价格/余额/仓位依次校验，任一失败即停止
- [ ] `FreezeHandler`：通过后冻结成功创建 OrderState；冻结失败（并发边界）→ INSUFFICIENT_BALANCE
- [ ] `RouteHandler`：通过 → SBE InternalNewOrder 发 Aeron；拒绝 → ExecutionReport(REJECTED) 发 Egress
- [ ] `ExecutionReportProcessor`：结算余额、更新 OrderState、发回报、更新参考价
- [ ] `CounterPipelineTest` 5 个测试通过
- [ ] 含 Part 1-3 合计 **102 个测试，0 Failures，0 Errors**

---

## 下一步：Part 5

Part 4 完成后，进入 **Part 5：ClusteredService 集成 + 验收清单**，包括：

1. `CounterClusteredService`：实现 Aeron Cluster `ClusteredService` 接口
2. 确定性要求：禁止 `System.currentTimeMillis()`，使用 `cluster.time()`
3. 快照序列化/反序列化（账户/仓位/订单状态完整保存）
4. `CounterServiceMain` 启动入口
5. Phase 3 完整验收检查清单
