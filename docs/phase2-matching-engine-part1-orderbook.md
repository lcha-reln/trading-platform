# Phase 2 撮合引擎核心实现 — Part 1：OrderBook 数据结构

> **目标：** 实现全内存订单簿核心数据结构，包括 `PriceLevel`、`OrderNode`、`OrderBook` 及对象池。
> 这是撮合引擎的基石，后续所有撮合逻辑都建立在本节实现之上。
>
> **前置条件：** Phase 1 完成，Maven 多模块骨架、SBE Schema、common-util 均已就绪  
> **本节验证目标：** OrderBook 增删查操作正确性单元测试全部通过

---

## 目录

1. [Phase 2 总览](#1-phase-2-总览)
2. [OrderNode 订单节点](#2-ordernode-订单节点)
3. [OrderNodePool 对象池](#3-ordernodepool-对象池)
4. [PriceLevel 价格档位](#4-pricelevel-价格档位)
5. [OrderBook 订单簿](#5-orderbook-订单簿)
6. [OrderBook 基础操作单元测试](#6-orderbook-基础操作单元测试)

---

## 1. Phase 2 总览

### 1.1 本阶段目标

Phase 2 对应设计文档第 6 节"撮合引擎详细设计"，分 5 个 Part 依次实现：

| Part           | 内容                                  | 验证目标                             |
|----------------|-------------------------------------|----------------------------------|
| **Part 1**（本文） | OrderBook 数据结构                      | 增删查单元测试通过                        |
| Part 2         | 撮合算法（Limit/Market/IOC/FOK/PostOnly） | 所有 edge case 单元测试通过              |
| Part 3         | Disruptor Pipeline + Aeron IPC 集成   | Pipeline 联调通过                    |
| Part 4         | 全场景单元测试                             | 边界 case 100% 覆盖                  |
| Part 5         | 性能基准测试                              | 单交易对 > 500K orders/sec，P99 < 5μs |

### 1.2 文件结构（本 Part 新增）

```
matching-engine/src/main/java/com/trading/matching/
├── orderbook/
│   ├── OrderNode.java          ← 订单节点（链表元素，对象池管理）
│   ├── OrderNodePool.java      ← 预分配对象池
│   ├── PriceLevel.java         ← 价格档位（含双向链表）
│   └── OrderBook.java          ← 订单簿主体
matching-engine/src/test/java/com/trading/matching/
└── orderbook/
    └── OrderBookStructureTest.java  ← 本 Part 单元测试
```

### 1.3 核心设计原则回顾

```
原则 1：全内存，热路径零 IO
  所有数据结构常驻内存，不访问数据库/磁盘。
  持久化由异步 Journal Service 在独立线程完成。

原则 2：零 GC
  OrderNode 由对象池预分配，成交/撤销后归还池而非 GC。
  所有集合使用 Agrona primitive 版本，避免装箱。

原则 3：Price-Time 优先级
  同价位订单按到达时间（链表插入顺序）排列。
  买盘价格从高到低，卖盘价格从低到高。

原则 4：O(log N) 价格档位操作
  使用 Agrona Long2ObjectHashMap + TreeMap 语义管理价格档位。
  同价位订单链表操作为 O(1)。
```

---

## 2. OrderNode 订单节点

`OrderNode` 是订单在订单簿中的运行时表示，存储撮合所需的全部字段，并作为双向链表节点使用。

文件：`matching-engine/src/main/java/com/trading/matching/orderbook/OrderNode.java`

```java
package com.trading.matching.orderbook;

import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import com.trading.sbe.TimeInForce;

/**
 * 订单节点 —— 订单在订单簿中的运行时表示。
 *
 * <p>设计要点：
 * <ul>
 *   <li>由 {@link OrderNodePool} 预分配，成交/撤销后通过 {@link #reset()} 归还，
 *       实现零 GC 复用。</li>
 *   <li>作为双向链表节点嵌入 {@link PriceLevel}，避免额外的包装对象。</li>
 *   <li>所有 long 字段使用固定精度整数，与 SBE 消息字段直接对应。</li>
 * </ul>
 *
 * @author Reln Ding
 */
public final class OrderNode {

    // ---- 订单基础字段（由柜台服务写入，撮合引擎只读）----

    /** 系统全局唯一订单 ID（Snowflake 生成）*/
    public long orderId;

    /** 账户 ID */
    public long accountId;

    /** 交易对 ID */
    public int symbolId;

    /** 买卖方向 */
    public byte side;           // Side.BUY = 1, Side.SELL = 2

    /** 订单类型 */
    public byte orderType;      // OrderType.LIMIT = 1, MARKET = 2, ...

    /** 有效时间类型 */
    public byte timeInForce;    // TimeInForce.GTC = 1, GTD = 2, GFD = 3

    /** 委托价格（固定精度整数，Market 单为 Long.MAX_VALUE 或 0）*/
    public long price;

    /** 原始委托数量（固定精度整数）*/
    public long quantity;

    /** 已成交数量 */
    public long filledQty;

    /** 剩余未成交数量 = quantity - filledQty */
    public long leavesQty;

    /** GTD 订单过期时间（纳秒 UTC epoch；非 GTD 订单填 0）*/
    public long expireTimeNs;

    /** 订单进入撮合引擎的时间戳（纳秒），用于 Time Priority 排序标记 */
    public long acceptTimestampNs;

    // ---- 双向链表指针（由 PriceLevel 管理）----

    /** 同价位链表中的前驱节点（时间更早）*/
    public OrderNode prev;

    /** 同价位链表中的后继节点（时间更晚）*/
    public OrderNode next;

    // ---- 生命周期方法 ----

    /**
     * 初始化节点字段（从对象池借出后调用）。
     * 所有字段显式赋值，防止复用脏数据。
     */
    public void init(final long orderId,
                     final long accountId,
                     final int  symbolId,
                     final byte side,
                     final byte orderType,
                     final byte timeInForce,
                     final long price,
                     final long quantity,
                     final long expireTimeNs,
                     final long acceptTimestampNs) {
        this.orderId           = orderId;
        this.accountId         = accountId;
        this.symbolId          = symbolId;
        this.side              = side;
        this.orderType         = orderType;
        this.timeInForce       = timeInForce;
        this.price             = price;
        this.quantity          = quantity;
        this.filledQty         = 0L;
        this.leavesQty         = quantity;
        this.expireTimeNs      = expireTimeNs;
        this.acceptTimestampNs = acceptTimestampNs;
        this.prev              = null;
        this.next              = null;
    }

    /**
     * 归还对象池前重置，防止持有外部引用导致内存泄漏。
     */
    public void reset() {
        this.orderId           = 0L;
        this.accountId         = 0L;
        this.symbolId          = 0;
        this.side              = 0;
        this.orderType         = 0;
        this.timeInForce       = 0;
        this.price             = 0L;
        this.quantity          = 0L;
        this.filledQty         = 0L;
        this.leavesQty         = 0L;
        this.expireTimeNs      = 0L;
        this.acceptTimestampNs = 0L;
        this.prev              = null;
        this.next              = null;
    }

    /** 是否已全部成交 */
    public boolean isFilled() {
        return leavesQty == 0L;
    }

    /** 是否为买单 */
    public boolean isBuy() {
        return side == Side.BUY.value();
    }

    /** 是否为卖单 */
    public boolean isSell() {
        return side == Side.SELL.value();
    }

    /** 是否为限价单 */
    public boolean isLimit() {
        return orderType == OrderType.LIMIT.value();
    }

    /** 是否为市价单 */
    public boolean isMarket() {
        return orderType == OrderType.MARKET.value();
    }

    /** 是否为 IOC */
    public boolean isIoc() {
        return orderType == OrderType.IOC.value();
    }

    /** 是否为 FOK */
    public boolean isFok() {
        return orderType == OrderType.FOK.value();
    }

    /** 是否为 PostOnly */
    public boolean isPostOnly() {
        return orderType == OrderType.POST_ONLY.value();
    }

    @Override
    public String toString() {
        return "OrderNode{orderId=" + orderId
            + ", side=" + side
            + ", price=" + price
            + ", qty=" + quantity
            + ", leavesQty=" + leavesQty
            + ", filled=" + filledQty
            + '}';
    }
}
```

---

## 3. OrderNodePool 对象池

`OrderNodePool` 是 `OrderNode` 的专用预分配池。系统启动时一次性创建所有节点，运行期借出/归还，实现热路径零 GC。

文件：`matching-engine/src/main/java/com/trading/matching/orderbook/OrderNodePool.java`

```java
package com.trading.matching.orderbook;

/**
 * OrderNode 专用预分配对象池。
 *
 * <p>设计约束：
 * <ul>
 *   <li>非线程安全 —— 撮合引擎单线程调用，无需同步。</li>
 *   <li>容量固定 —— 启动时根据预估最大在途订单数配置；
 *       建议初始值 = 单交易对最大挂单数 × 1.5。</li>
 *   <li>池空时返回 {@code null}，调用方须处理（记录告警、触发熔断）。</li>
 * </ul>
 *
 * @author Reln Ding
 */
public final class OrderNodePool {

    /** 默认容量：支持 100 万在途订单 */
    public static final int DEFAULT_CAPACITY = 1_000_000;

    private final OrderNode[] pool;
    private int top;   // 栈顶指针，指向下一个可借出的槽位

    public OrderNodePool(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got: " + capacity);
        }
        this.pool = new OrderNode[capacity];
        this.top  = capacity;
        for (int i = 0; i < capacity; i++) {
            pool[i] = new OrderNode();
        }
    }

    /**
     * 从池中借出一个 OrderNode。
     *
     * @return 可用的 OrderNode，或 {@code null}（池空时）
     */
    public OrderNode borrow() {
        if (top == 0) {
            return null;   // 池已耗尽，调用方需处理
        }
        return pool[--top];
    }

    /**
     * 将 OrderNode 归还池中。
     * 调用方须在归还前调用 {@link OrderNode#reset()} 清空字段。
     *
     * @param node 待归还节点（不得为 null）
     */
    public void release(final OrderNode node) {
        if (top < pool.length) {
            pool[top++] = node;
        }
        // 超出容量说明有重复归还 BUG，静默丢弃并可在此处加监控
    }

    /** 当前可借出数量 */
    public int available() {
        return top;
    }

    /** 池总容量 */
    public int capacity() {
        return pool.length;
    }

    /** 是否已空 */
    public boolean isEmpty() {
        return top == 0;
    }
}
```

---

## 4. PriceLevel 价格档位

`PriceLevel` 代表订单簿中同一价格的所有挂单，内部维护一条按时间优先排列的双向链表。

文件：`matching-engine/src/main/java/com/trading/matching/orderbook/PriceLevel.java`

```java
package com.trading.matching.orderbook;

/**
 * 价格档位 —— 同一价格下所有挂单的集合。
 *
 * <p>内部结构：双向链表（head → ... → tail），按时间先进先出排列。
 * <ul>
 *   <li>新订单追加到 tail（时间最晚）。</li>
 *   <li>撮合时从 head 开始消费（时间最早，优先成交）。</li>
 *   <li>撤单时通过 {@link OrderNode#prev}/{@link OrderNode#next} 直接 O(1) 摘除。</li>
 * </ul>
 *
 * @author Reln Ding
 */
public final class PriceLevel {

    /** 价格（固定精度整数，与 OrderNode.price 一致）*/
    public final long price;

    /** 链表头节点（时间最早，优先撮合）*/
    public OrderNode head;

    /** 链表尾节点（时间最晚，新单追加处）*/
    public OrderNode tail;

    /** 该档位所有挂单的剩余数量之和（leavesQty 之和）*/
    public long totalQty;

    /** 该档位挂单数量 */
    public int orderCount;

    public PriceLevel(final long price) {
        this.price = price;
        this.head = null;
        this.tail = null;
        this.totalQty = 0L;
        this.orderCount = 0;
    }

    /**
     * 将订单追加到链表尾部（新单入簿）。
     *
     * @param node 已初始化的 OrderNode
     */
    public void addOrder(final OrderNode node) {
        node.prev = tail;
        node.next = null;
        if (tail != null) {
            tail.next = node;
        } else {
            head = node;   // 首个节点，同时设 head
        }
        tail = node;
        totalQty += node.leavesQty;
        orderCount++;
    }

    /**
     * 从链表中摘除指定节点（撤单或全成交后调用）。
     *
     * @param node 要摘除的节点
     */
    public void removeOrder(final OrderNode node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;   // 摘除的是 head
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;   // 摘除的是 tail
        }
        node.prev = null;
        node.next = null;
        totalQty -= node.leavesQty;
        orderCount--;
    }

    /**
     * 成交后减少档位总量（部分成交时调用，不移除节点）。
     *
     * @param filledQty 本次成交数量
     */
    public void reduceQty(final long filledQty) {
        totalQty -= filledQty;
    }

    /** 档位是否为空（无挂单）*/
    public boolean isEmpty() {
        return orderCount == 0;
    }

    @Override
    public String toString() {
        return "PriceLevel{price=" + price
                + ", totalQty=" + totalQty
                + ", orderCount=" + orderCount
                + '}';
    }
}
```

---

## 5. OrderBook 订单簿

`OrderBook` 是单个交易对的完整订单簿，管理买盘和卖盘的所有价格档位，提供挂单、撤单、查询等基础操作。  
撮合逻辑（`match()` 方法）在 Part 2 中实现，本节只实现数据结构和基础操作。

文件：`matching-engine/src/main/java/com/trading/matching/orderbook/OrderBook.java`

```java
package com.trading.matching.orderbook;

import org.agrona.collections.Long2ObjectHashMap;

import java.util.TreeMap;

/**
 * 单交易对订单簿。
 *
 * <p>数据结构选型：
 * <ul>
 *   <li><b>买盘（bids）</b>：{@link TreeMap}（降序），Key = 价格，Value = {@link PriceLevel}。
 *       最优买价 = {@code bids.firstKey()}。</li>
 *   <li><b>卖盘（asks）</b>：{@link TreeMap}（升序），Key = 价格，Value = {@link PriceLevel}。
 *       最优卖价 = {@code asks.firstKey()}。</li>
 *   <li><b>订单索引</b>：{@link Long2ObjectHashMap}（Agrona 无装箱），
 *       orderId → OrderNode，O(1) 查找。</li>
 * </ul>
 *
 * <p>线程安全：单线程。每个交易对一个 OrderBook 实例，绑定独立 CPU 核心。
 *
 * @author Reln Ding
 */
public final class OrderBook {

    /** 交易对 ID */
    public final int symbolId;

    /** 买盘价格档位：价格降序（最优买价在首位）*/
    // 注：生产环境可换为 Agrona LongTreeMap；此处用 JDK TreeMap 降低依赖复杂度
    private final TreeMap<Long, PriceLevel> bids;

    /** 卖盘价格档位：价格升序（最优卖价在首位）*/
    private final TreeMap<Long, PriceLevel> asks;

    /**
     * 订单快速索引：orderId → OrderNode。
     * 用于 O(1) 撤单查找。
     */
    private final Long2ObjectHashMap<OrderNode> orderIndex;

    /** OrderNode 对象池（由外部注入，多个 OrderBook 可共享同一个池）*/
    private final OrderNodePool nodePool;

    // ---- 统计字段 ----

    /** 最新成交价 */
    private long lastTradePrice;

    /** 最新成交量 */
    private long lastTradeQty;

    /** 24 小时成交量（累计，需定时重置）*/
    private long volume24h;

    /** 24 小时成交额（累计）*/
    private long turnover24h;

    /** 当前簿中挂单总数 */
    private int totalOrderCount;

    public OrderBook(final int symbolId, final OrderNodePool nodePool) {
        this.symbolId = symbolId;
        this.nodePool = nodePool;
        // TreeMap.reverseOrder() 使 firstKey() 始终返回最大价格（最优买价）
        this.bids = new TreeMap<>(java.util.Comparator.reverseOrder());
        this.asks = new TreeMap<>();
        this.orderIndex = new Long2ObjectHashMap<>(65536, 0.6f);
    }

    // ================================================================
    // 挂单操作
    // ================================================================

    /**
     * 将订单加入订单簿（限价单挂单）。
     *
     * <p>此方法只管"加入"，不执行撮合逻辑。撮合在 {@code OrderMatcher} 中完成。
     *
     * @param node 已初始化的 OrderNode（由调用方从对象池借出并填充）
     */
    public void addOrder(final OrderNode node) {
        final TreeMap<Long, PriceLevel> side = node.isBuy() ? bids : asks;
        final PriceLevel level = side.computeIfAbsent(node.price, PriceLevel::new);
        level.addOrder(node);
        orderIndex.put(node.orderId, node);
        totalOrderCount++;
    }

    // ================================================================
    // 撤单操作
    // ================================================================

    /**
     * 根据订单 ID 撤单（从订单簿中移除）。
     *
     * @param orderId 要撤销的订单 ID
     * @return 被撤销的 OrderNode（调用方负责归还对象池），或 {@code null}（订单不存在）
     */
    public OrderNode removeOrder(final long orderId) {
        final OrderNode node = orderIndex.remove(orderId);
        if (node == null) {
            return null;
        }
        final TreeMap<Long, PriceLevel> side = node.isBuy() ? bids : asks;
        final PriceLevel level = side.get(node.price);
        if (level != null) {
            level.removeOrder(node);
            if (level.isEmpty()) {
                side.remove(node.price);   // 档位空了则清除，防止内存泄漏
            }
        }
        totalOrderCount--;
        return node;
    }

    /**
     * 成交后从订单簿中移除全成交节点，并更新价格档位统计。
     * 部分成交时只更新 {@link PriceLevel#totalQty}，不移除节点。
     *
     * @param node      成交节点
     * @param filledQty 本次成交数量
     * @param isFull    是否全部成交
     */
    public void onFill(final OrderNode node, final long filledQty, final boolean isFull) {
        node.filledQty += filledQty;
        node.leavesQty -= filledQty;

        final TreeMap<Long, PriceLevel> side = node.isBuy() ? bids : asks;
        final PriceLevel level = side.get(node.price);
        if (level != null) {
            level.reduceQty(filledQty);
            if (isFull) {
                level.removeOrder(node);
                orderIndex.remove(node.orderId);
                totalOrderCount--;
                if (level.isEmpty()) {
                    side.remove(node.price);
                }
            }
        }
    }

    // ================================================================
    // 查询操作
    // ================================================================

    /**
     * 根据订单 ID 查询挂单节点。
     *
     * @param orderId 订单 ID
     * @return OrderNode，或 {@code null}（不存在）
     */
    public OrderNode getOrder(final long orderId) {
        return orderIndex.get(orderId);
    }

    /**
     * 获取最优买价档位（买一价）。
     *
     * @return 最优买价 {@link PriceLevel}，或 {@code null}（买盘为空）
     */
    public PriceLevel bestBid() {
        return bids.isEmpty() ? null : bids.firstEntry().getValue();
    }

    /**
     * 获取最优卖价档位（卖一价）。
     *
     * @return 最优卖价 {@link PriceLevel}，或 {@code null}（卖盘为空）
     */
    public PriceLevel bestAsk() {
        return asks.isEmpty() ? null : asks.firstEntry().getValue();
    }

    /**
     * 获取最优买价（价格值）。
     *
     * @return 最优买价，或 {@code Long.MIN_VALUE}（买盘为空）
     */
    public long bestBidPrice() {
        return bids.isEmpty() ? Long.MIN_VALUE : bids.firstKey();
    }

    /**
     * 获取最优卖价（价格值）。
     *
     * @return 最优卖价，或 {@code Long.MAX_VALUE}（卖盘为空）
     */
    public long bestAskPrice() {
        return asks.isEmpty() ? Long.MAX_VALUE : asks.firstKey();
    }

    /**
     * 获取买盘（只读视图，用于深度推送）。
     */
    public TreeMap<Long, PriceLevel> getBids() {
        return bids;
    }

    /**
     * 获取卖盘（只读视图，用于深度推送）。
     */
    public TreeMap<Long, PriceLevel> getAsks() {
        return asks;
    }

    /** 买盘是否为空 */
    public boolean isBidEmpty() {
        return bids.isEmpty();
    }

    /** 卖盘是否为空 */
    public boolean isAskEmpty() {
        return asks.isEmpty();
    }

    /** 当前簿中挂单总数 */
    public int getTotalOrderCount() {
        return totalOrderCount;
    }

    // ================================================================
    // 统计更新
    // ================================================================

    /**
     * 记录成交统计（每笔成交后调用）。
     *
     * @param tradePrice 成交价
     * @param tradeQty   成交量
     * @param turnover   成交额（= tradePrice × tradeQty / precision factor）
     */
    public void recordTrade(final long tradePrice,
                            final long tradeQty,
                            final long turnover) {
        lastTradePrice = tradePrice;
        lastTradeQty = tradeQty;
        volume24h += tradeQty;
        turnover24h += turnover;
    }

    public long getLastTradePrice() {
        return lastTradePrice;
    }

    public long getLastTradeQty() {
        return lastTradeQty;
    }

    public long getVolume24h() {
        return volume24h;
    }

    public long getTurnover24h() {
        return turnover24h;
    }

    // ================================================================
    // 对象池访问
    // ================================================================

    /**
     * 从内部对象池借出一个 OrderNode（供撮合器使用）。
     */
    public OrderNode borrowNode() {
        return nodePool.borrow();
    }

    /**
     * 将 OrderNode 归还内部对象池。
     * 调用方须先调用 {@link OrderNode#reset()}。
     */
    public void releaseNode(final OrderNode node) {
        node.reset();
        nodePool.release(node);
    }

    @Override
    public String toString() {
        return "OrderBook{symbolId=" + symbolId
                + ", bids=" + bids.size() + " levels"
                + ", asks=" + asks.size() + " levels"
                + ", orders=" + totalOrderCount
                + ", bestBid=" + bestBidPrice()
                + ", bestAsk=" + bestAskPrice()
                + '}';
    }
}
```

---

## 6. OrderBook 基础操作单元测试

验证挂单、撤单、查询等数据结构操作的正确性，不涉及撮合逻辑（撮合测试在 Part 4）。

文件：`matching-engine/src/test/java/com/trading/matching/orderbook/OrderBookStructureTest.java`

```java
package com.trading.matching.orderbook;

import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import com.trading.sbe.TimeInForce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OrderBook 数据结构单元测试。
 *
 * 覆盖：挂单、撤单、价格档位管理、对象池、统计字段。
 * 不包含撮合逻辑（见 Part 4 OrderMatcherTest）。
 *
 * @author Reln Ding
 */
class OrderBookStructureTest {

    private static final int SYMBOL_ID = 1;
    private static final int POOL_SIZE = 1024;

    private OrderNodePool pool;
    private OrderBook book;

    @BeforeEach
    void setUp() {
        pool = new OrderNodePool(POOL_SIZE);
        book = new OrderBook(SYMBOL_ID, pool);
    }

    // ----------------------------------------------------------------
    // 辅助：构造 OrderNode
    // ----------------------------------------------------------------

    private OrderNode makeOrder(final long orderId,
                                final long accountId,
                                final byte side,
                                final long price,
                                final long quantity) {
        final OrderNode node = pool.borrow();
        assertNotNull(node, "Pool is empty");
        node.init(orderId, accountId, SYMBOL_ID,
                side,
                OrderType.LIMIT.value(),
                TimeInForce.GTC.value(),
                price, quantity,
                0L, System.nanoTime());
        return node;
    }

    // ================================================================
    // 挂单测试
    // ================================================================

    @Nested
    @DisplayName("addOrder - 挂单")
    class AddOrderTests {

        @Test
        @DisplayName("买单入簿后可通过 bestBid 查到")
        void shouldAddBuyOrder() {
            final OrderNode node = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            book.addOrder(node);

            assertFalse(book.isBidEmpty());
            assertEquals(5000_00L, book.bestBidPrice());
            assertNotNull(book.bestBid());
            assertEquals(100L, book.bestBid().totalQty);
            assertEquals(1, book.bestBid().orderCount);
            assertEquals(1, book.getTotalOrderCount());
        }

        @Test
        @DisplayName("卖单入簿后可通过 bestAsk 查到")
        void shouldAddSellOrder() {
            final OrderNode node = makeOrder(2L, 1002L, Side.SELL.value(), 5001_00L, 50L);
            book.addOrder(node);

            assertFalse(book.isAskEmpty());
            assertEquals(5001_00L, book.bestAskPrice());
            assertEquals(50L, book.bestAsk().totalQty);
            assertEquals(1, book.getTotalOrderCount());
        }

        @Test
        @DisplayName("同价位多单按时间顺序排列")
        void shouldMaintainTimePriorityAtSamePrice() {
            final OrderNode n1 = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            final OrderNode n2 = makeOrder(2L, 1002L, Side.BUY.value(), 5000_00L, 200L);
            final OrderNode n3 = makeOrder(3L, 1003L, Side.BUY.value(), 5000_00L, 300L);
            book.addOrder(n1);
            book.addOrder(n2);
            book.addOrder(n3);

            final PriceLevel level = book.bestBid();
            assertNotNull(level);
            assertEquals(3, level.orderCount);
            assertEquals(600L, level.totalQty);

            // 链表顺序：n1 → n2 → n3
            assertSame(n1, level.head);
            assertSame(n2, level.head.next);
            assertSame(n3, level.tail);
            assertNull(level.head.prev);
            assertNull(level.tail.next);
        }

        @Test
        @DisplayName("不同价位独立档位，买盘按价格降序")
        void shouldCreateSeparatePriceLevels() {
            book.addOrder(makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L));
            book.addOrder(makeOrder(2L, 1001L, Side.BUY.value(), 4999_00L, 200L));
            book.addOrder(makeOrder(3L, 1001L, Side.BUY.value(), 5001_00L, 300L));

            assertEquals(3, book.getBids().size());
            // 最优买价是 5001_00
            assertEquals(5001_00L, book.bestBidPrice());
            assertEquals(3, book.getTotalOrderCount());
        }

        @Test
        @DisplayName("通过 getOrder 可按 orderId 查到挂单")
        void shouldIndexByOrderId() {
            final OrderNode node = makeOrder(42L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            book.addOrder(node);

            final OrderNode found = book.getOrder(42L);
            assertSame(node, found);
            assertNull(book.getOrder(999L));
        }
    }

    // ================================================================
    // 撤单测试
    // ================================================================

    @Nested
    @DisplayName("removeOrder - 撤单")
    class RemoveOrderTests {

        @Test
        @DisplayName("撤单后订单从索引和档位中移除")
        void shouldRemoveOrderFromBookAndIndex() {
            final OrderNode node = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            book.addOrder(node);

            final OrderNode removed = book.removeOrder(1L);
            assertSame(node, removed);
            assertNull(book.getOrder(1L));
            assertTrue(book.isBidEmpty());
            assertEquals(0, book.getTotalOrderCount());
        }

        @Test
        @DisplayName("撤中间节点，链表结构保持正确")
        void shouldRemoveMiddleNodeCorrectly() {
            final OrderNode n1 = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            final OrderNode n2 = makeOrder(2L, 1002L, Side.BUY.value(), 5000_00L, 200L);
            final OrderNode n3 = makeOrder(3L, 1003L, Side.BUY.value(), 5000_00L, 300L);
            book.addOrder(n1);
            book.addOrder(n2);
            book.addOrder(n3);

            book.removeOrder(2L);  // 撤中间节点 n2

            final PriceLevel level = book.bestBid();
            assertEquals(2, level.orderCount);
            assertEquals(400L, level.totalQty);
            assertSame(n1, level.head);
            assertSame(n3, level.tail);
            assertSame(n3, n1.next);
            assertSame(n1, n3.prev);
            assertNull(n2.prev);
            assertNull(n2.next);
        }

        @Test
        @DisplayName("撤最后一单后，该价格档位被清除")
        void shouldRemovePriceLevelWhenEmpty() {
            book.addOrder(makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L));
            book.removeOrder(1L);

            assertTrue(book.isBidEmpty());
            assertEquals(0, book.getBids().size());
        }

        @Test
        @DisplayName("撤不存在的订单返回 null")
        void shouldReturnNullForNonExistentOrder() {
            assertNull(book.removeOrder(999L));
        }
    }

    // ================================================================
    // onFill 测试（成交更新）
    // ================================================================

    @Nested
    @DisplayName("onFill - 成交更新")
    class OnFillTests {

        @Test
        @DisplayName("部分成交只更新 leavesQty 和 totalQty，不移除节点")
        void shouldUpdateQtyOnPartialFill() {
            final OrderNode node = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            book.addOrder(node);

            book.onFill(node, 30L, false);

            assertEquals(30L, node.filledQty);
            assertEquals(70L, node.leavesQty);
            assertEquals(70L, book.bestBid().totalQty);
            assertEquals(1, book.getTotalOrderCount());   // 仍在簿中
            assertNotNull(book.getOrder(1L));
        }

        @Test
        @DisplayName("全部成交后节点从订单簿中移除")
        void shouldRemoveNodeOnFullFill() {
            final OrderNode node = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            book.addOrder(node);

            book.onFill(node, 100L, true);

            assertNull(book.getOrder(1L));
            assertTrue(book.isBidEmpty());
            assertEquals(0, book.getTotalOrderCount());
        }
    }

    // ================================================================
    // 对象池测试
    // ================================================================

    @Nested
    @DisplayName("OrderNodePool - 对象池")
    class PoolTests {

        @Test
        @DisplayName("借出再归还后可再次借出（复用）")
        void shouldReuseNodeAfterRelease() {
            final OrderNode n = pool.borrow();
            assertNotNull(n);
            final int before = pool.available();
            n.reset();
            pool.release(n);
            assertEquals(before + 1, pool.available());
            assertSame(n, pool.borrow());
        }

        @Test
        @DisplayName("池空时 borrow 返回 null")
        void shouldReturnNullWhenEmpty() {
            final OrderNodePool tiny = new OrderNodePool(1);
            assertNotNull(tiny.borrow());
            assertNull(tiny.borrow());   // 第二次借出，池空
        }

        @Test
        @DisplayName("容量为 0 时构造抛 IllegalArgumentException")
        void shouldThrowOnZeroCapacity() {
            assertThrows(IllegalArgumentException.class, () -> new OrderNodePool(0));
        }

        @Test
        @DisplayName("负容量时构造抛 IllegalArgumentException")
        void shouldThrowOnNegativeCapacity() {
            assertThrows(IllegalArgumentException.class, () -> new OrderNodePool(-1));
        }
    }

    // ================================================================
    // PriceLevel 测试
    // ================================================================

    @Nested
    @DisplayName("PriceLevel - 价格档位")
    class PriceLevelTests {

        @Test
        @DisplayName("新建档位应为空")
        void shouldBeEmptyOnCreation() {
            final PriceLevel level = new PriceLevel(5000_00L);
            assertTrue(level.isEmpty());
            assertEquals(0L, level.totalQty);
            assertEquals(0, level.orderCount);
            assertNull(level.head);
            assertNull(level.tail);
        }

        @Test
        @DisplayName("addOrder 后 head 和 tail 均指向该节点")
        void shouldSetHeadAndTailOnFirstAdd() {
            final PriceLevel level = new PriceLevel(5000_00L);
            final OrderNode n = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            level.addOrder(n);

            assertSame(n, level.head);
            assertSame(n, level.tail);
            assertNull(n.prev);
            assertNull(n.next);
        }

        @Test
        @DisplayName("removeOrder 后 isEmpty 返回 true")
        void shouldBeEmptyAfterRemovingLastOrder() {
            final PriceLevel level = new PriceLevel(5000_00L);
            final OrderNode n = makeOrder(1L, 1001L, Side.BUY.value(), 5000_00L, 100L);
            level.addOrder(n);
            level.removeOrder(n);

            assertTrue(level.isEmpty());
            assertNull(level.head);
            assertNull(level.tail);
        }
    }

    // ================================================================
    // 统计字段测试
    // ================================================================

    @Nested
    @DisplayName("统计字段")
    class StatisticsTests {

        @Test
        @DisplayName("recordTrade 正确累加 volume24h 和 turnover24h")
        void shouldAccumulateTradeStatistics() {
            book.recordTrade(5000_00L, 100L, 500_000L);
            book.recordTrade(5001_00L, 50L, 250_050L);

            assertEquals(5001_00L, book.getLastTradePrice());
            assertEquals(50L, book.getLastTradeQty());
            assertEquals(150L, book.getVolume24h());
            assertEquals(750_050L, book.getTurnover24h());
        }
    }
}
```

### 6.1 运行单元测试

```bash
cd trading-platform
mvn test -pl matching-engine -Dtest=OrderBookStructureTest -Dcheckstyle.skip=true
# 期望：Tests run: 17, Failures: 0, Errors: 0
```

---

## Part 1 完成检查清单

完成本 Part 后，验证以下所有项目：

- [ ] `OrderNode` 实现完整，`init()` / `reset()` 字段覆盖无遗漏
- [ ] `OrderNodePool` 在池空时正确返回 `null`，负容量时抛出异常
- [ ] `PriceLevel` 链表操作（addOrder / removeOrder）指针正确
- [ ] `OrderBook.addOrder` 正确维护买盘降序、卖盘升序
- [ ] `OrderBook.removeOrder` 空档位时正确清除 `TreeMap` 条目
- [ ] `OrderBook.onFill` 部分成交不移除节点，全成交正确移除
- [ ] `OrderBookStructureTest` 全部 17 个测试通过

---

## 下一步：Part 2

Part 1 完成后，进入 **Part 2：撮合算法实现**，包括：

1. `OrderMatcher` 撮合主入口（`match()` / `cancel()` / `modify()`）
2. `Limit` 标准限价单撮合（Price-Time Priority）
3. `Market` 市价单处理
4. `IOC` 立即成交或撤销
5. `FOK` 流动性预检 + 全量成交或全撤
6. `PostOnly` 仅挂单（有成交则拒绝）
