# Phase 2 撮合引擎核心实现 — Part 2：撮合算法

> **目标：** 在 Part 1 订单簿数据结构之上，实现完整的撮合逻辑，支持
> `Limit / Market / IOC / FOK / PostOnly` 五种订单类型，以及撤单和改单。
>
> **前置条件：** Part 1 完成，`OrderBook` 数据结构测试全部通过  
> **本节验证目标：** 撮合逻辑单元测试通过（edge case 覆盖详见 Part 4）

---

## 目录

1. [撮合结果数据结构](#1-撮合结果数据结构)
2. [MatchResult 事件列表](#2-matchresult-事件列表)
3. [OrderMatcher 撮合主入口](#3-ordermatcher-撮合主入口)
4. [各订单类型撮合逻辑详解](#4-各订单类型撮合逻辑详解)
5. [撤单与改单](#5-撤单与改单)
6. [快速冒烟测试](#6-快速冒烟测试)

---

## 1. 撮合结果数据结构

撮合引擎每次撮合可能产生 **0 到 N 笔** 成交。为避免热路径对象分配，所有成交事件写入预分配的
`MatchEvent` 数组，通过 `MatchEventList` 统一管理。

### 1.1 MatchEvent

文件：`matching-engine/src/main/java/com/trading/matching/orderbook/MatchEvent.java`

```java
package com.trading.matching.orderbook;

/**
 * 单笔成交事件（撮合引擎内部使用）。
 *
 * <p>由 {@link MatchEventList} 预分配并复用，热路径不产生新对象。
 * 撮合完成后，Pipeline 的下游 Handler 读取此列表并分发（回报、行情、日志）。
 *
 * @author Reln Ding
 */
public final class MatchEvent {

    /** 撮合全局序列号（由 SequenceAssignHandler 分配）*/
    public long sequenceNo;

    /** 交易对 ID */
    public int symbolId;

    /** Maker 订单 ID（被动方，挂单方）*/
    public long makerOrderId;

    /** Taker 订单 ID（主动方，进攻方）*/
    public long takerOrderId;

    /** Maker 账户 ID */
    public long makerAccountId;

    /** Taker 账户 ID */
    public long takerAccountId;

    /** 成交价格（Maker 报价优先）*/
    public long price;

    /** 成交数量 */
    public long quantity;

    /** Maker 买卖方向 */
    public byte makerSide;

    /** Maker 手续费（固定精度整数，可为负表示返佣）*/
    public long makerFee;

    /** Taker 手续费 */
    public long takerFee;

    /** 成交时间戳（纳秒）*/
    public long timestampNs;

    /** Maker 成交后是否已全部成交（用于通知柜台服务更新状态）*/
    public boolean makerFullyFilled;

    /** Taker 成交后是否已全部成交 */
    public boolean takerFullyFilled;

    /** 重置字段（归还列表时调用）*/
    public void reset() {
        sequenceNo = 0L;
        symbolId = 0;
        makerOrderId = 0L;
        takerOrderId = 0L;
        makerAccountId = 0L;
        takerAccountId = 0L;
        price = 0L;
        quantity = 0L;
        makerSide = 0;
        makerFee = 0L;
        takerFee = 0L;
        timestampNs = 0L;
        makerFullyFilled = false;
        takerFullyFilled = false;
    }
}
```

### 1.2 MatchEventList

文件：`matching-engine/src/main/java/com/trading/matching/orderbook/MatchEventList.java`

```java
package com.trading.matching.orderbook;

/**
 * 可复用的成交事件列表（每次撮合复用同一实例）。
 *
 * <p>容量上限 = 单笔撮合最多可产生的成交笔数。
 * 实际场景中，一笔市价单最多扫穿 N 个价格档位，
 * 建议初始容量 = 最大档位扫穿数 × 2（留余量），如 2048。
 *
 * @author Reln Ding
 */
public final class MatchEventList {

    private final MatchEvent[] events;
    private int size;

    public MatchEventList(final int capacity) {
        this.events = new MatchEvent[capacity];
        for (int i = 0; i < capacity; i++) {
            events[i] = new MatchEvent();
        }
        this.size = 0;
    }

    /**
     * 申请下一个可写入的 MatchEvent 槽位。
     *
     * @return 可写入的 MatchEvent（已重置），或 {@code null}（已满）
     */
    public MatchEvent next() {
        if (size >= events.length) {
            return null;   // 不应发生，可在此处告警
        }
        final MatchEvent e = events[size++];
        e.reset();
        return e;
    }

    /** 重置列表（每次新撮合开始前调用）*/
    public void clear() {
        size = 0;
    }

    /** 已产生的成交事件数量 */
    public int size() {
        return size;
    }

    /** 是否有成交事件 */
    public boolean hasEvents() {
        return size > 0;
    }

    /** 按索引读取成交事件（只读）*/
    public MatchEvent get(final int index) {
        return events[index];
    }
}
```

---

## 2. MatchResult 事件列表

撮合完成后的整体结果，除成交列表外还携带 Taker 的最终状态（挂单/撤销/拒绝），供下游 Handler 使用。

文件：`matching-engine/src/main/java/com/trading/matching/orderbook/MatchResult.java`

```java
package com.trading.matching.orderbook;

/**
 * 单次撮合的完整结果。
 *
 * <p>每次调用 {@link OrderMatcher#match(OrderNode, MatchResult)} 后，
 * 调用方通过此对象了解：
 * <ol>
 *   <li>产生了哪些成交（{@link #events}）</li>
 *   <li>Taker 订单的最终状态（{@link TakerStatus}）</li>
 * </ol>
 *
 * @author Reln Ding
 */
public final class MatchResult {

    /**
     * Taker 订单的最终处置结果。
     */
    public enum TakerStatus {
        /** Taker 挂入订单簿（有剩余数量，GTC 语义）*/
        RESTING,
        /** Taker 全部成交 */
        FULLY_FILLED,
        /** Taker 部分成交后被撤销（IOC 语义）*/
        PARTIAL_FILL_CANCELLED,
        /** Taker 零成交被撤销（FOK 预检失败 / IOC 无流动性 / PostOnly 有成交）*/
        CANCELLED,
        /** Taker 被拒绝（FOK 预检失败，使用此状态区分于普通撤销）*/
        REJECTED
    }

    /** 本次撮合产生的成交事件列表 */
    public final MatchEventList events;

    /** Taker 最终状态 */
    public TakerStatus takerStatus;

    /** Taker 订单节点引用（用于下游生成回报消息）*/
    public OrderNode takerNode;

    public MatchResult(final int eventListCapacity) {
        this.events = new MatchEventList(eventListCapacity);
    }

    /** 重置（每次撮合开始前调用）*/
    public void reset(final OrderNode taker) {
        events.clear();
        takerStatus = null;
        takerNode = taker;
    }
}
```

---

## 3. OrderMatcher 撮合主入口

`OrderMatcher` 是撮合逻辑的核心，持有对 `OrderBook` 的引用，按订单类型路由到对应的撮合策略。

文件：`matching-engine/src/main/java/com/trading/matching/matcher/OrderMatcher.java`

```java
package com.trading.matching.matcher;

import com.trading.matching.orderbook.MatchEvent;
import com.trading.matching.orderbook.MatchResult;
import com.trading.matching.orderbook.MatchResult.TakerStatus;
import com.trading.matching.orderbook.OrderBook;
import com.trading.matching.orderbook.OrderNode;
import com.trading.matching.orderbook.PriceLevel;
import com.trading.sbe.Side;

/**
 * 撮合主入口。
 *
 * <p>职责：
 * <ol>
 *   <li>按订单类型路由（Limit / Market / IOC / FOK / PostOnly）。</li>
 *   <li>执行 Price-Time Priority 撮合循环。</li>
 *   <li>将撮合结果写入 {@link MatchResult}（零对象分配）。</li>
 *   <li>维护 {@link OrderBook} 的数据一致性。</li>
 * </ol>
 *
 * <p>线程安全：非线程安全，单线程调用（每个交易对独立实例）。
 *
 * @author Reln Ding
 */
public final class OrderMatcher {

    /** 市价买单使用 Long.MAX_VALUE 作为哨兵价格，确保能匹配所有卖价 */
    public static final long MARKET_BUY_PRICE = Long.MAX_VALUE;

    /** 市价卖单使用 0 作为哨兵价格，确保能匹配所有买价 */
    public static final long MARKET_SELL_PRICE = 0L;

    /** 手续费率精度：1/1_000_000（百万分之一）*/
    private static final long FEE_RATE_DIVISOR = 1_000_000L;

    private final OrderBook orderBook;

    /** Maker 手续费率（百万分之一，如 1000 = 0.1%）*/
    private final int makerFeeRateMicros;

    /** Taker 手续费率 */
    private final int takerFeeRateMicros;

    /** 纳秒时间提供器（撮合引擎内部使用 Cluster 时钟）*/
    private final com.trading.util.NanoTimeProvider timeProvider;

    public OrderMatcher(final OrderBook orderBook,
                        final int makerFeeRateMicros,
                        final int takerFeeRateMicros,
                        final com.trading.util.NanoTimeProvider timeProvider) {
        this.orderBook = orderBook;
        this.makerFeeRateMicros = makerFeeRateMicros;
        this.takerFeeRateMicros = takerFeeRateMicros;
        this.timeProvider = timeProvider;
    }

    // ================================================================
    // 公开 API
    // ================================================================

    /**
     * 对新订单执行撮合。
     *
     * <p>调用方须在调用前：
     * <ol>
     *   <li>从对象池借出 OrderNode 并初始化。</li>
     *   <li>调用 {@link MatchResult#reset(OrderNode)}。</li>
     * </ol>
     *
     * 调用后，依据 {@link MatchResult#takerStatus} 决定是否归还对象池：
     * <ul>
     *   <li>{@code RESTING}：节点已挂入订单簿，不得归还。</li>
     *   <li>其余状态：节点未挂入簿，调用方负责归还对象池。</li>
     * </ul>
     *
     * @param taker  Taker 订单节点（已初始化）
     * @param result 撮合结果容器（已 reset）
     */
    public void match(final OrderNode taker, final MatchResult result) {
        switch (taker.orderType) {
            case 1 -> matchLimit(taker, result);    // LIMIT
            case 2 -> matchMarket(taker, result);   // MARKET
            case 3 -> matchIoc(taker, result);      // IOC
            case 4 -> matchFok(taker, result);      // FOK
            case 5 -> matchPostOnly(taker, result); // POST_ONLY
            default -> throw new IllegalArgumentException(
                    "Unknown orderType: " + taker.orderType);
        }
    }

    /**
     * 撤销挂单。
     *
     * @param orderId 要撤销的订单 ID
     * @return 被撤销的 OrderNode（调用方负责归还对象池），或 {@code null}（不存在）
     */
    public OrderNode cancel(final long orderId) {
        return orderBook.removeOrder(orderId);
    }

    // ================================================================
    // Limit 限价单
    // ================================================================

    /**
     * 限价单撮合（Price-Time Priority）。
     *
     * <p>流程：
     * <ol>
     *   <li>尝试与对手盘撮合，直到 Taker 剩余量为 0 或无可撮合档位。</li>
     *   <li>若 Taker 仍有剩余：挂入订单簿，状态 {@code RESTING}。</li>
     *   <li>若全成交：状态 {@code FULLY_FILLED}，节点不入簿。</li>
     * </ol>
     */
    private void matchLimit(final OrderNode taker, final MatchResult result) {
        executeCoreMatch(taker, result);

        if (taker.leavesQty > 0) {
            orderBook.addOrder(taker);
            result.takerStatus = TakerStatus.RESTING;
        } else {
            result.takerStatus = TakerStatus.FULLY_FILLED;
        }
    }

    // ================================================================
    // Market 市价单
    // ================================================================

    /**
     * 市价单撮合。
     *
     * <p>市价单强制 IOC 语义：若对手盘深度不足，剩余量直接撤销，不挂单。
     * 价格哨兵：买单用 {@link #MARKET_BUY_PRICE}，卖单用 {@link #MARKET_SELL_PRICE}。
     */
    private void matchMarket(final OrderNode taker, final MatchResult result) {
        // 市价单使用哨兵价格，保证能穿透所有对手盘
        taker.price = taker.isBuy() ? MARKET_BUY_PRICE : MARKET_SELL_PRICE;
        executeCoreMatch(taker, result);

        if (taker.leavesQty > 0) {
            // 市价单不允许挂单，剩余量撤销
            result.takerStatus = taker.filledQty > 0
                    ? TakerStatus.PARTIAL_FILL_CANCELLED
                    : TakerStatus.CANCELLED;
        } else {
            result.takerStatus = TakerStatus.FULLY_FILLED;
        }
    }

    // ================================================================
    // IOC 立即成交或撤销
    // ================================================================

    /**
     * IOC 撮合：能成交多少就成交多少，剩余立即撤销。
     */
    private void matchIoc(final OrderNode taker, final MatchResult result) {
        executeCoreMatch(taker, result);

        if (taker.leavesQty > 0) {
            result.takerStatus = taker.filledQty > 0
                    ? TakerStatus.PARTIAL_FILL_CANCELLED
                    : TakerStatus.CANCELLED;
        } else {
            result.takerStatus = TakerStatus.FULLY_FILLED;
        }
    }

    // ================================================================
    // FOK 全成交或全撤
    // ================================================================

    /**
     * FOK 撮合：先预检流动性，足够则全量成交，不足则全部拒绝（零成交）。
     *
     * <p>预检阶段只遍历价格档位，不修改任何状态。
     */
    private void matchFok(final OrderNode taker, final MatchResult result) {
        if (!canFillCompletely(taker)) {
            // 流动性不足，直接拒绝，不产生任何成交
            result.takerStatus = TakerStatus.REJECTED;
            return;
        }
        // 流动性充足，执行完整撮合
        executeCoreMatch(taker, result);
        // FOK 撮合后 leavesQty 必须为 0（由预检保证）
        result.takerStatus = TakerStatus.FULLY_FILLED;
    }

    /**
     * FOK 流动性预检：遍历对手盘档位，判断是否能完全满足 Taker 数量。
     *
     * <p>只读操作，不修改订单簿。
     */
    private boolean canFillCompletely(final OrderNode taker) {
        long remaining = taker.quantity;
        final java.util.TreeMap<Long, PriceLevel> oppositeSide =
                taker.isBuy() ? orderBook.getAsks() : orderBook.getBids();

        for (final java.util.Map.Entry<Long, PriceLevel> entry : oppositeSide.entrySet()) {
            final long levelPrice = entry.getKey();
            if (taker.isBuy() && levelPrice > taker.price) break; // 卖价超出买价上限
            if (taker.isSell() && levelPrice < taker.price) break; // 买价低于卖价下限

            remaining -= Math.min(remaining, entry.getValue().totalQty);
            if (remaining == 0) return true;
        }
        return false;
    }

    // ================================================================
    // PostOnly 仅挂单
    // ================================================================

    /**
     * PostOnly 撮合：若与任何现有挂单价格匹配（会立即成交），则拒绝该订单。
     * 只有"不会立即成交"的情况下才将订单挂入订单簿。
     */
    private void matchPostOnly(final OrderNode taker, final MatchResult result) {
        // 检查是否会立即成交
        final boolean wouldMatch;
        if (taker.isBuy()) {
            final long bestAsk = orderBook.bestAskPrice();
            wouldMatch = bestAsk != Long.MAX_VALUE && bestAsk <= taker.price;
        } else {
            final long bestBid = orderBook.bestBidPrice();
            wouldMatch = bestBid != Long.MIN_VALUE && bestBid >= taker.price;
        }

        if (wouldMatch) {
            result.takerStatus = TakerStatus.CANCELLED;
        } else {
            orderBook.addOrder(taker);
            result.takerStatus = TakerStatus.RESTING;
        }
    }

    // ================================================================
    // 核心撮合循环（Price-Time Priority）
    // ================================================================

    /**
     * 核心撮合循环，被 Limit / Market / IOC / FOK 共用。
     *
     * <p>算法：
     * <pre>
     * WHILE taker.leavesQty > 0 AND oppositeSide NOT EMPTY:
     *   bestLevel = oppositeSide.first()
     *   IF priceNotMatched(bestLevel): BREAK
     *   FOR each maker IN bestLevel (time priority, head → tail):
     *     fillQty = min(taker.leavesQty, maker.leavesQty)
     *     emit MatchEvent(maker, taker, fillPrice=maker.price, qty=fillQty)
     *     updateFills(maker, taker, fillQty)
     *     IF maker fully filled: removeFromBook(maker)
     *     IF taker fully filled: BREAK
     * </pre>
     */
    private void executeCoreMatch(final OrderNode taker, final MatchResult result) {
        final java.util.TreeMap<Long, PriceLevel> oppositeSide =
                taker.isBuy() ? orderBook.getAsks() : orderBook.getBids();

        while (taker.leavesQty > 0 && !oppositeSide.isEmpty()) {
            final java.util.Map.Entry<Long, PriceLevel> bestEntry = oppositeSide.firstEntry();
            final long bestPrice = bestEntry.getKey();

            // 价格匹配检查
            if (taker.isBuy() && bestPrice > taker.price) break;  // 最低卖价 > 买单价格
            if (taker.isSell() && bestPrice < taker.price) break;  // 最高买价 < 卖单价格

            final PriceLevel level = bestEntry.getValue();
            OrderNode maker = level.head;

            while (maker != null && taker.leavesQty > 0) {
                final long fillQty = Math.min(taker.leavesQty, maker.leavesQty);
                final long fillPrice = maker.price;   // Maker 价格优先
                final long nowNs = timeProvider.nanoTime();

                // 写入成交事件
                final MatchEvent event = result.events.next();
                if (event != null) {
                    event.symbolId = orderBook.symbolId;
                    event.makerOrderId = maker.orderId;
                    event.takerOrderId = taker.orderId;
                    event.makerAccountId = maker.accountId;
                    event.takerAccountId = taker.accountId;
                    event.price = fillPrice;
                    event.quantity = fillQty;
                    event.makerSide = maker.side;
                    event.makerFee = calcFee(fillPrice, fillQty, makerFeeRateMicros);
                    event.takerFee = calcFee(fillPrice, fillQty, takerFeeRateMicros);
                    event.timestampNs = nowNs;
                    event.makerFullyFilled = (maker.leavesQty == fillQty);
                    event.takerFullyFilled = (taker.leavesQty == fillQty);
                }

                // 更新统计
                orderBook.recordTrade(fillPrice, fillQty, fillPrice * fillQty);

                // 更新 Maker 成交量
                final boolean makerFull = maker.leavesQty == fillQty;
                final OrderNode nextMaker = maker.next;  // 先保存 next，removeOrder 会清空指针
                orderBook.onFill(maker, fillQty, makerFull);
                if (makerFull) {
                    // Maker 全成交，节点已从订单簿摘除，归还对象池
                    orderBook.releaseNode(maker);
                }

                // 更新 Taker 成交量（直接修改，不调用 onFill，因为 Taker 尚未入簿）
                taker.filledQty += fillQty;
                taker.leavesQty -= fillQty;

                maker = makerFull ? nextMaker : null;  // 若 Maker 全成交则继续下一个；否则 Taker 已满
            }

            // 若该档位已清空，oppositeSide.firstEntry() 下轮会自动跳到下一档位
            // （orderBook.onFill 在全成交时已删除空档位）
        }
    }

    // ================================================================
    // 手续费计算
    // ================================================================

    /**
     * 计算手续费（热路径）。
     *
     * <p>fee = price × quantity × feeRateMicros / (precision_factor × 1_000_000)
     * 此处简化为：fee = (price × quantity / precision_scale) × feeRateMicros / 1_000_000
     * 实际精度因子需结合 SymbolConfig，此处使用整数直接运算近似。
     *
     * @param price         成交价（固定精度 long）
     * @param quantity      成交量（固定精度 long）
     * @param feeRateMicros 费率（百万分之一）
     * @return 手续费（固定精度 long）
     */
    private static long calcFee(final long price,
                                final long quantity,
                                final int feeRateMicros) {
        // 避免 long 溢出：先除后乘
        return (price / FEE_RATE_DIVISOR) * quantity * feeRateMicros
                + (price % FEE_RATE_DIVISOR) * quantity * feeRateMicros / FEE_RATE_DIVISOR;
    }
}
```

---

## 4. 各订单类型撮合逻辑详解

### 4.1 Limit 限价单流程图

```
入单 (Limit Buy, price=P, qty=Q)
          │
          ▼
  取卖盘最优价 bestAsk
          │
    ┌─────▼─────┐
    │ bestAsk ≤ P?│
    └─────┬─────┘
         否│              是│
           │          ┌────▼────────────────────────────┐
      挂入买盘         │   撮合最优卖价档位（Time Priority）  │
      RESTING         │   fillQty = min(taker, maker)    │
                      │   emit MatchEvent                 │
                      │   Maker 全成交 → 移除，归还对象池  │
                      └────────────────┬────────────────┘
                                       │
                              ┌────────▼────────┐
                              │ taker.leavesQty │
                              │     == 0?       │
                              └──┬──────────┬──┘
                               是│          否│
                          FULLY_FILLED   继续下一档位 →
                                          无更多可撮合档位
                                               │
                                          挂入买盘 RESTING
```

### 4.2 FOK 预检流程图

```
入单 (FOK, qty=Q)
          │
          ▼
  遍历对手盘（只读）
  累加可成交量 available
          │
    ┌─────▼──────┐
    │ available  │
    │   ≥ Q?     │
    └─────┬──────┘
        否│           是│
          │       执行 executeCoreMatch
     REJECTED          │
                  leavesQty 必为 0
                  FULLY_FILLED
```

### 4.3 PostOnly 决策流程图

```
入单 (PostOnly Buy, price=P)
          │
          ▼
  取卖盘最优价 bestAsk
          │
    ┌─────▼─────┐
    │ bestAsk ≤ P?│  ← 若成立则会立即成交
    └─────┬─────┘
         是│              否│
           │          挂入买盘 RESTING
       CANCELLED
   （拒绝，不成交，不挂单）
```

### 4.4 订单类型行为汇总

| 订单类型       | 有剩余量时                          | 无流动性时          | 挂单    |
|------------|--------------------------------|----------------|-------|
| `Limit`    | 挂入订单簿 `RESTING`                | 直接挂单           | 是     |
| `Market`   | 剩余量撤销 `PARTIAL_FILL_CANCELLED` | `CANCELLED`    | 否     |
| `IOC`      | 剩余量撤销 `PARTIAL_FILL_CANCELLED` | `CANCELLED`    | 否     |
| `FOK`      | 预检不足直接 `REJECTED`              | `REJECTED`     | 否     |
| `PostOnly` | 会成交则 `CANCELLED`               | 直接挂单 `RESTING` | 仅当不成交 |

---

## 5. 撤单与改单

### 5.1 撤单实现

撤单通过 `OrderMatcher.cancel(orderId)` 直接委托给 `OrderBook.removeOrder()`，已在 Part 1 实现。

撮合引擎对撤单的处理流程：

```
收到 InternalCancelOrder (orderId=X)
          │
          ▼
  OrderMatcher.cancel(X)
  → OrderBook.removeOrder(X)
          │
    ┌─────▼──────┐
    │  找到节点?  │
    └──┬───────┬─┘
      是│      否│
        │    返回 null（ORDER_NOT_FOUND）
        │
   从链表/索引移除
   返回 OrderNode
        │
   调用方归还对象池
   通知柜台服务解冻资金
```

### 5.2 改单（Modify）

改单拆分为"撤单 + 重新下单"，在柜台服务层处理（非撮合引擎内部操作）：

```
收到 ModifyOrderRequest (orderId=X, newPrice=P', newQty=Q')
          │
  柜台服务：
  1. 校验账户余额差额
  2. 发送 InternalCancelOrder(X)      → 撮合引擎撤原单
  3. 解冻原单资金，冻结新单资金
  4. 发送 InternalNewOrder(newPrice, newQty) → 撮合引擎新建订单
          │
  注意：改单等价于"撤旧单 + 新单"，新单失去时间优先权（排到同价位末尾）
```

> **设计说明：** 改单不作为撮合引擎的原子操作，由柜台服务拆解为两步，这是绝大多数交易所的做法。
> 好处是撮合引擎逻辑简单；代价是改单用户的时间优先权会丢失。

---

## 6. 快速冒烟测试

验证撮合主流程（完整 edge case 覆盖见 Part 4）。

文件：`matching-engine/src/test/java/com/trading/matching/matcher/OrderMatcherSmokeTest.java`

```java
package com.trading.matching.matcher;

import com.trading.matching.orderbook.*;
import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import com.trading.sbe.TimeInForce;
import com.trading.util.NanoTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OrderMatcher 冒烟测试（快速验证核心路径，完整 edge case 见 Part 4）。
 *
 * @author Reln Ding
 */
class OrderMatcherSmokeTest {

    private static final int SYMBOL_ID = 1;
    private static final int MAKER_FEE_MICROS = 1_000;   // 0.1%
    private static final int TAKER_FEE_MICROS = 2_000;   // 0.2%

    private OrderNodePool nodePool;
    private OrderBook book;
    private OrderMatcher matcher;
    private MatchResult result;

    @BeforeEach
    void setUp() {
        nodePool = new OrderNodePool(1024);
        book = new OrderBook(SYMBOL_ID, nodePool);
        matcher = new OrderMatcher(book, MAKER_FEE_MICROS, TAKER_FEE_MICROS,
                NanoTimeProvider.SYSTEM);
        result = new MatchResult(256);
    }

    // ---- 辅助方法 ----

    private OrderNode makeLimitOrder(final long orderId, final long accountId,
                                     final byte side, final long price, final long qty) {
        final OrderNode n = nodePool.borrow();
        assertNotNull(n);
        n.init(orderId, accountId, SYMBOL_ID, side,
                OrderType.LIMIT.value(), TimeInForce.GTC.value(),
                price, qty, 0L, System.nanoTime());
        return n;
    }

    private OrderNode makeOrderOfType(final long orderId, final long accountId,
                                      final byte side, final long price, final long qty,
                                      final byte orderType) {
        final OrderNode n = nodePool.borrow();
        assertNotNull(n);
        n.init(orderId, accountId, SYMBOL_ID, side,
                orderType, TimeInForce.GTC.value(),
                price, qty, 0L, System.nanoTime());
        return n;
    }

    // ================================================================
    // Limit 撮合
    // ================================================================

    @Test
    @DisplayName("Limit: 买单价格 >= 卖单价格时完全撮合")
    void limitShouldMatchWhenPriceCross() {
        // 先挂卖单
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 100L);
        result.reset(sell);
        matcher.match(sell, result);
        assertEquals(MatchResult.TakerStatus.RESTING, result.takerStatus);
        assertFalse(result.events.hasEvents());

        // 买单价格 >= 卖单价格，触发撮合
        final OrderNode buy = makeLimitOrder(2L, 1002L, Side.BUY.value(), 5000_00L, 100L);
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.FULLY_FILLED, result.takerStatus);
        assertEquals(1, result.events.size());
        final MatchEvent e = result.events.get(0);
        assertEquals(5000_00L, e.price);
        assertEquals(100L, e.quantity);
        assertEquals(1L, e.makerOrderId);
        assertEquals(2L, e.takerOrderId);
        assertTrue(e.makerFullyFilled);
        assertTrue(e.takerFullyFilled);
        assertTrue(book.isAskEmpty());
        assertTrue(book.isBidEmpty());
    }

    @Test
    @DisplayName("Limit: 买单价格 < 卖单价格时挂单等待")
    void limitShouldRestWhenNoCross() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5001_00L, 100L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode buy = makeLimitOrder(2L, 1002L, Side.BUY.value(), 5000_00L, 100L);
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.RESTING, result.takerStatus);
        assertFalse(result.events.hasEvents());
        assertEquals(2, book.getTotalOrderCount());
    }

    @Test
    @DisplayName("Limit: 部分成交后剩余量挂单")
    void limitShouldRestRemainingAfterPartialFill() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 30L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode buy = makeLimitOrder(2L, 1002L, Side.BUY.value(), 5000_00L, 100L);
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.RESTING, result.takerStatus);
        assertEquals(1, result.events.size());
        assertEquals(30L, result.events.get(0).quantity);
        assertEquals(70L, buy.leavesQty);
        assertEquals(70L, book.bestBid().totalQty);
        assertTrue(book.isAskEmpty());
    }

    // ================================================================
    // Market 撮合
    // ================================================================

    @Test
    @DisplayName("Market: 完全消耗卖盘")
    void marketBuyShouldConsumeAsks() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 100L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode buy = makeOrderOfType(2L, 1002L, Side.BUY.value(), 0L, 100L,
                OrderType.MARKET.value());
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.FULLY_FILLED, result.takerStatus);
        assertEquals(1, result.events.size());
        assertTrue(book.isAskEmpty());
    }

    @Test
    @DisplayName("Market: 深度不足时剩余量撤销")
    void marketShouldCancelRemainingWhenInsufficientDepth() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 30L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode buy = makeOrderOfType(2L, 1002L, Side.BUY.value(), 0L, 100L,
                OrderType.MARKET.value());
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.PARTIAL_FILL_CANCELLED, result.takerStatus);
        assertEquals(30L, buy.filledQty);
        assertEquals(70L, buy.leavesQty);
        assertTrue(book.isAskEmpty());
        assertTrue(book.isBidEmpty());  // Market 单剩余不入簿
    }

    @Test
    @DisplayName("Market: 对手盘为空时直接撤销")
    void marketShouldCancelWhenNoBids() {
        final OrderNode sell = makeOrderOfType(1L, 1001L, Side.SELL.value(), 0L, 100L,
                OrderType.MARKET.value());
        result.reset(sell);
        matcher.match(sell, result);

        assertEquals(MatchResult.TakerStatus.CANCELLED, result.takerStatus);
        assertFalse(result.events.hasEvents());
    }

    // ================================================================
    // IOC 撮合
    // ================================================================

    @Test
    @DisplayName("IOC: 部分成交后剩余量撤销，不挂单")
    void iocShouldCancelRemainingAfterPartialFill() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 40L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode buy = makeOrderOfType(2L, 1002L, Side.BUY.value(), 5000_00L, 100L,
                OrderType.IOC.value());
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.PARTIAL_FILL_CANCELLED, result.takerStatus);
        assertEquals(40L, buy.filledQty);
        assertTrue(book.isBidEmpty());   // IOC 剩余不入簿
    }

    // ================================================================
    // FOK 撮合
    // ================================================================

    @Test
    @DisplayName("FOK: 深度充足时全量成交")
    void fokShouldFillWhenSufficientDepth() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 100L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode buy = makeOrderOfType(2L, 1002L, Side.BUY.value(), 5000_00L, 100L,
                OrderType.FOK.value());
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.FULLY_FILLED, result.takerStatus);
        assertEquals(100L, buy.filledQty);
    }

    @Test
    @DisplayName("FOK: 深度不足时零成交拒绝")
    void fokShouldRejectWhenInsufficientDepth() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 50L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode buy = makeOrderOfType(2L, 1002L, Side.BUY.value(), 5000_00L, 100L,
                OrderType.FOK.value());
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.REJECTED, result.takerStatus);
        assertFalse(result.events.hasEvents());   // 零成交
        assertEquals(1, book.getTotalOrderCount());  // 卖单仍在簿中
    }

    // ================================================================
    // PostOnly 撮合
    // ================================================================

    @Test
    @DisplayName("PostOnly: 无对手盘时挂单成功")
    void postOnlyShouldRestWhenNoOpposite() {
        final OrderNode buy = makeOrderOfType(1L, 1001L, Side.BUY.value(), 5000_00L, 100L,
                OrderType.POST_ONLY.value());
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.RESTING, result.takerStatus);
        assertFalse(result.events.hasEvents());
        assertEquals(5000_00L, book.bestBidPrice());
    }

    @Test
    @DisplayName("PostOnly: 会立即成交时拒绝挂单")
    void postOnlyShouldCancelWhenWouldMatch() {
        // 先挂卖单
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 100L);
        result.reset(sell);
        matcher.match(sell, result);

        // PostOnly 买单价格 >= 最优卖价 → 拒绝
        final OrderNode buy = makeOrderOfType(2L, 1002L, Side.BUY.value(), 5000_00L, 100L,
                OrderType.POST_ONLY.value());
        result.reset(buy);
        matcher.match(buy, result);

        assertEquals(MatchResult.TakerStatus.CANCELLED, result.takerStatus);
        assertFalse(result.events.hasEvents());
        assertEquals(1, book.getTotalOrderCount());  // 卖单仍在簿
    }

    // ================================================================
    // 撤单
    // ================================================================

    @Test
    @DisplayName("cancel: 撤单后节点从订单簿移除")
    void cancelShouldRemoveOrderFromBook() {
        final OrderNode sell = makeLimitOrder(1L, 1001L, Side.SELL.value(), 5000_00L, 100L);
        result.reset(sell);
        matcher.match(sell, result);

        final OrderNode cancelled = matcher.cancel(1L);
        assertNotNull(cancelled);
        assertEquals(1L, cancelled.orderId);
        assertTrue(book.isAskEmpty());
    }

    @Test
    @DisplayName("cancel: 撤不存在的订单返回 null")
    void cancelShouldReturnNullForUnknownOrder() {
        assertNull(matcher.cancel(999L));
    }
}
```

### 6.1 运行冒烟测试

```bash
cd trading-platform
mvn test -pl matching-engine -Dtest=OrderMatcherSmokeTest -Dcheckstyle.skip=true
# 期望：Tests run: 14, Failures: 0, Errors: 0
```

---

## Part 2 完成检查清单

- [ ] `MatchEvent` / `MatchEventList` / `MatchResult` 结构定义完整
- [ ] `OrderMatcher` 按 orderType 正确路由（5 种类型 + default 抛异常）
- [ ] `executeCoreMatch` 撮合后订单簿状态一致（空档位被清除，对象池归还）
- [ ] FOK 预检为只读操作（不修改订单簿）
- [ ] PostOnly 逻辑：会成交 → `CANCELLED`；不会成交 → `RESTING`
- [ ] `OrderMatcherSmokeTest` 14 个测试全部通过

---

## 下一步：Part 3

Part 2 完成后，进入 **Part 3：Disruptor Pipeline 与 Aeron IPC 集成**，包括：

1. `MatchingDisruptor` —— 配置 3 段 Pipeline（SequenceAssign → Match → 并行输出）
2. `InboundOrderSubscriber` —— Aeron IPC 读入 → 写入 RingBuffer
3. `ExecutionReportHandler` —— 成交回报 → Aeron IPC stream=3 → 柜台服务
4. `MarketDataPublishHandler` —— 行情变更 → Aeron IPC stream=4 → 推送服务
5. `JournalPublishHandler` —— 事件日志 → Aeron IPC stream=5 → Journal 服务
6. 完整链路集成冒烟测试
