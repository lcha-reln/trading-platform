# 高性能虚拟货币交易柜台 — 详细方案设计

**技术栈：** Java 21 · Aeron · Aeron Cluster · LMAX Disruptor · SBE · Agrona · Netty  
**目标性能：** 单机 >1,000,000 orders/sec · 端到端延迟 P99 < 10μs  
**品种支持：** 现货 (Spot) · 永续合约 (Perp) · 期货 (Futures)  
**高可用方案：** Aeron Cluster Raft 三节点共识

---

## 目录

1. [系统全景图](#1-系统全景图)
2. [模块职责划分](#2-模块职责划分)
3. [消息协议设计 (SBE)](#3-消息协议设计-sbe)
4. [通信拓扑设计 (Aeron)](#4-通信拓扑设计-aeron)
5. [柜台服务详细设计](#5-柜台服务详细设计)
6. [撮合引擎详细设计](#6-撮合引擎详细设计)
7. [推送服务详细设计](#7-推送服务详细设计)
8. [高可用方案 (Aeron Cluster)](#8-高可用方案-aeron-cluster)
9. [持久化与恢复方案](#9-持久化与恢复方案)
10. [风控模块设计](#10-风控模块设计)
11. [数据模型设计](#11-数据模型设计)
12. [性能优化策略](#12-性能优化策略)
13. [部署架构](#13-部署架构)
14. [项目工程结构](#14-项目工程结构)
15. [关键流程时序图](#15-关键流程时序图)
16. [里程碑计划](#16-里程碑计划)

---

## 1. 系统全景图

```
                          ┌──────────────────────────────────────────────────────────┐
                          │                     客户端接入层                           │
                          │         REST API (下单/查询)  WebSocket (行情/回报)         │
                          └────────────────────────┬─────────────────────────────────┘
                                                   │ HTTPS / WSS
                          ┌────────────────────────▼─────────────────────────────────┐
                          │               Gateway Service (Netty)                     │
                          │  • 连接管理 & 会话认证 (JWT/HMAC)                          │
                          │  • 请求反序列化 → SBE 编码                                 │
                          │  • 限流 (令牌桶，per-account)                              │
                          │  • 将 SBE 消息写入 Aeron Publication                       │
                          └────────────────────────┬─────────────────────────────────┘
                                                   │ Aeron UDP (跨节点) / IPC (同机)
             ┌─────────────────────────────────────▼──────────────────────────────────────┐
             │                        Aeron Cluster (3 节点 Raft)                          │
             │  ┌─────────────────────────────────────────────────────────────────────┐   │
             │  │                      柜台服务 (Counter Service)                       │   │
             │  │  • 账户余额管理        • 仓位管理 (Spot/Perp/Futures)                  │   │
             │  │  • 风控前置校验        • 订单生命周期追踪                               │   │
             │  │  • 公共数据维护        • 手续费计算                                    │   │
             │  │  Disruptor: InboundRingBuffer → RiskHandler → RouteHandler           │   │
             │  └──────────────────────────┬──────────────────────────────────────────┘   │
             │                             │ Aeron IPC                                    │
             │  ┌──────────────────────────▼──────────────────────────────────────────┐   │
             │  │                    撮合引擎 (Matching Engine)                          │   │
             │  │  • 全内存订单簿 (每交易对独立实例)                                       │   │
             │  │  • Price-Time 优先级撮合                                               │   │
             │  │  • 支持 Limit/Market/IOC/FOK/Post-Only 订单类型                        │   │
             │  │  Disruptor: MatchingRingBuffer → SequenceHandler → MatchHandler       │   │
             │  └──────────┬─────────────────────────────────┬───────────────────────-┘   │
             │             │ Aeron IPC                        │ Aeron IPC                  │
             └─────────────┼──────────────────────────────────┼────────────────────────────┘
                           │                                  │
          ┌────────────────▼──────────────┐    ┌─────────────▼─────────────────────────┐
          │    Journal Service (持久化)    │    │       Push Service (推送服务)          │
          │  • 顺序写事件日志               │    │  • 订阅撮合引擎成交回报                 │
          │  • 故障恢复回放                 │    │  • 深度行情聚合 & 广播                  │
          │  • 冷备份归档                   │    │  • WebSocket 推送 (Netty)             │
          └───────────────────────────────┘    └───────────────────────────────────────┘
```

---

## 2. 模块职责划分

### 2.1 模块清单

| 模块 | 部署单元 | 核心职责 | 关键技术 |
|------|---------|---------|---------|
| **gateway-service** | 独立进程（多实例） | 客户端接入、鉴权、限流、SBE 编码 | Netty、Aeron UDP |
| **counter-service** | Aeron Cluster 状态机 | 账户/仓位/订单管理、风控、公共数据 | Disruptor、Agrona |
| **matching-engine** | 内嵌在 Cluster 状态机 | 订单簿撮合、成交分发 | Disruptor、Agrona |
| **push-service** | 独立进程（多实例） | 行情推送、成交回报推送 | Aeron、Netty WebSocket |
| **journal-service** | 独立进程 | 事件日志写入、状态恢复 | Aeron、MappedFile |
| **common** | 共享库 | SBE 编解码、领域模型、工具类 | SBE、Agrona |

### 2.2 进程拓扑

```
[Gateway-1]  [Gateway-2]  [Gateway-N]
     │              │           │
     └──────────────┴─────┬─────┘
                          │ Aeron UDP  (Channel: aeron:udp?endpoint=cluster-ingress)
                ┌─────────▼──────────────────────────────┐
                │    Aeron Cluster Ingress              │
                │  ┌─────────┐  ┌─────────┐  ┌───────┐ │
                │  │ Node-0  │  │ Node-1  │  │Node-2 │ │
                │  │(Leader) │  │(Follow) │  │(Follow│ │
                │  └────┬────┘  └────┬────┘  └───┬───┘ │
                │       └────────────┴────────────┘     │
                │              Raft Log Replication      │
                └────────────────────┬───────────────────┘
                                     │ Aeron IPC
                         ┌───────────┴──────────────┐
                         │                          │
                  [Journal-Service]         [Push-Service-1..N]
```

---

## 3. 消息协议设计 (SBE)

### 3.1 设计原则

- 所有消息使用 **SBE (Simple Binary Encoding)** 定义，固定长度，零拷贝编解码
- 价格、数量统一用 **long 类型表示固定精度整数**（精度因子存于交易对配置）
- 时间戳统一用 **纳秒 UTC epoch long**
- 枚举类型用 byte/short 表示
- 消息头固定 8 字节：`blockLength(2) + templateId(2) + schemaId(2) + version(2)`

### 3.2 消息分类

#### 入站消息（客户端 → 柜台）

| 消息 | templateId | 说明 |
|------|-----------|------|
| `NewOrderRequest` | 1 | 新建订单 |
| `CancelOrderRequest` | 2 | 撤销订单 |
| `ModifyOrderRequest` | 3 | 改单（价格/数量） |
| `QueryOrderRequest` | 4 | 查询订单 |
| `QueryPositionRequest` | 5 | 查询仓位 |
| `QueryBalanceRequest` | 6 | 查询余额 |

#### 出站消息（柜台 → 客户端回报）

| 消息 | templateId | 说明 |
|------|-----------|------|
| `ExecutionReport` | 101 | 订单状态变更回报（新建/部分成交/全成交/撤销/拒绝） |
| `OrderReject` | 102 | 订单被拒绝（含拒绝原因） |
| `PositionUpdate` | 103 | 仓位变更推送 |
| `BalanceUpdate` | 104 | 余额变更推送 |

#### 内部消息（柜台 → 撮合引擎）

| 消息 | templateId | 说明 |
|------|-----------|------|
| `InternalNewOrder` | 201 | 经风控通过后的订单 |
| `InternalCancelOrder` | 202 | 撤单指令 |
| `InternalModifyOrder` | 203 | 改单指令 |

#### 撮合回报（撮合引擎 → 柜台 / 推送）

| 消息 | templateId | 说明 |
|------|-----------|------|
| `MatchResult` | 301 | 成交结果（可能包含多笔成交） |
| `OrderBookUpdate` | 302 | 订单簿变更（用于深度推送） |
| `TradeEvent` | 303 | 成交事件（用于成交流推送） |

### 3.3 关键消息字段设计

#### NewOrderRequest

```
Field              Type     Description
─────────────────────────────────────────────────────
correlationId      int64    客户端自定义唯一ID
accountId          int64    账户ID
symbolId           int32    交易对ID（映射表在柜台维护）
side               int8     1=Buy, 2=Sell
orderType          int8     1=Limit, 2=Market, 3=IOC, 4=FOK, 5=PostOnly
timeInForce        int8     1=GTC, 2=GTD, 3=GFD
price              int64    固定精度整数价格 (0 for Market)
quantity           int64    固定精度整数数量
leverage           int16    杠杆倍数 (1~125, Spot=1)
clientOrderId      char[32] 客户端订单号
timestamp          int64    客户端时间戳 (ns)
```

#### ExecutionReport

```
Field              Type     Description
─────────────────────────────────────────────────────
orderId            int64    系统订单ID（全局唯一，由柜台分配）
correlationId      int64    对应 NewOrderRequest.correlationId
accountId          int64    账户ID
symbolId           int32    交易对ID
execType           int8     0=New, 1=PartialFill, 2=Fill, 3=Cancel, 4=Reject
orderStatus        int8     0=New, 1=PartialFill, 2=Filled, 3=Cancelled, 4=Rejected
side               int8     买卖方向
price              int64    委托价格
quantity           int64    委托数量
filledQty          int64    累计成交数量
lastFillPrice      int64    本次成交价格
lastFillQty        int64    本次成交数量
leavesQty          int64    剩余未成交数量
fee                int64    本次手续费
feeAsset           int32    手续费币种ID
timestamp          int64    系统时间戳 (ns)
```

#### MatchResult

```
Field              Type     Description
─────────────────────────────────────────────────────
sequenceNo         int64    撮合序列号（全局递增）
symbolId           int32    交易对ID
makerOrderId       int64    Maker 订单ID
takerOrderId       int64    Taker 订单ID
makerAccountId     int64    Maker 账户ID
takerAccountId     int64    Taker 账户ID
price              int64    成交价格
quantity           int64    成交数量
makerSide          int8     Maker 买卖方向
makerFee           int64    Maker 手续费（可为负，即返佣）
takerFee           int64    Taker 手续费
timestamp          int64    成交时间戳 (ns)
```

---

## 4. 通信拓扑设计 (Aeron)

### 4.1 Channel 规划

| 通道名 | 类型 | 方向 | 说明 |
|--------|------|------|------|
| `aeron:ipc` stream=1 | IPC | Gateway → Counter | 入站订单流 |
| `aeron:ipc` stream=2 | IPC | Counter → MatchEngine | 内部订单流 |
| `aeron:ipc` stream=3 | IPC | MatchEngine → Counter | 成交回报流 |
| `aeron:ipc` stream=4 | IPC | MatchEngine → Push | 行情/成交事件流 |
| `aeron:ipc` stream=5 | IPC | MatchEngine → Journal | 持久化事件流 |
| `aeron:udp?endpoint=...` | UDP | Gateway → Cluster Ingress | 跨进程入站（多网关） |
| `aeron:udp?endpoint=...` | UDP | Cluster Egress → Gateway | 回报下发 |
| `aeron:udp?endpoint=...` | UDP | Push → Client WebSocket | 行情订阅（经 Push Service 转换） |

### 4.2 Aeron Media Driver 部署策略

- **同机部署**：Counter + MatchEngine 使用共享 Media Driver（`aeron:ipc`），无网络开销
- **跨机通信**：Gateway 与 Cluster 之间走 `aeron:udp`，启用独立 Media Driver
- **Media Driver 模式**：生产环境使用 **Embedded Media Driver** 内嵌于各进程，减少上下文切换

### 4.3 流量控制与背压

- Aeron Publication 在 Subscriber 消费不及时时返回 `BACK_PRESSURED`
- Counter 层面设置 **入站限流**，超出背压阈值时直接拒绝新订单并返回 `SYSTEM_BUSY`
- 撮合引擎 RingBuffer 满时触发**限流熔断**，防止内存溢出

### 4.4 Idle Strategy 选型

| 场景 | IdleStrategy | 说明 |
|------|-------------|------|
| 撮合引擎热路径 | `BusySpinIdleStrategy` | 全速轮询，最低延迟，独占 CPU 核心 |
| Counter 处理线程 | `BusySpinIdleStrategy` | 同上 |
| Gateway 收发线程 | `SleepingMillisIdleStrategy(0)` | 轻量自旋 |
| Journal 写入线程 | `SleepingIdleStrategy(1ms)` | 允许稍高延迟 |
| Push 广播线程 | `SleepingMillisIdleStrategy(1)` | 允许毫秒级延迟 |

---

## 5. 柜台服务详细设计

### 5.1 总体架构

柜台服务作为 `Aeron Cluster` 的 `ClusteredService` 实现，所有状态变更经 Raft 日志复制后有序执行，天然保证主备一致。

```
Cluster Ingress (Aeron)
        │
        ▼
┌───────────────────────────────────────────────────────┐
│                   Counter Service                      │
│                                                       │
│  ┌─────────────────────────────────────────────────┐  │
│  │              Inbound Disruptor Pipeline          │  │
│  │                                                  │  │
│  │  [RingBuffer 2^20]                               │  │
│  │       │                                          │  │
│  │  ┌────▼────────┐                                 │  │
│  │  │ AuthHandler │  JWT 验签 / API Key 校验         │  │
│  │  └────┬────────┘                                 │  │
│  │  ┌────▼────────┐                                 │  │
│  │  │SymbolHandler│  交易对状态校验（是否开放、暂停）  │  │
│  │  └────┬────────┘                                 │  │
│  │  ┌────▼────────┐                                 │  │
│  │  │ RiskHandler │  余额/仓位/限额校验               │  │
│  │  └────┬────────┘                                 │  │
│  │  ┌────▼────────┐                                 │  │
│  │  │ FreezeHandler│ 冻结资金/保证金                 │  │
│  │  └────┬────────┘                                 │  │
│  │  ┌────▼────────┐                                 │  │
│  │  │ RouteHandler│  写入 Aeron IPC → MatchEngine   │  │
│  │  └─────────────┘                                 │  │
│  └─────────────────────────────────────────────────┘  │
│                                                       │
│  ┌─────────────────────────────────────────────────┐  │
│  │           Execution Report Processor             │  │
│  │  订阅撮合引擎回报 → 更新仓位/余额 → 回报客户端     │  │
│  └─────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────┘
```

### 5.2 账户模型

#### 余额结构（Spot）

每个账户针对每种资产维护三个字段：

```
AccountBalance {
  accountId   : int64
  assetId     : int32
  available   : int64   // 可用余额（固定精度）
  frozen      : int64   // 冻结余额（挂单占用）
  total       : int64   // total = available + frozen
}
```

**余额操作规则：**

| 操作 | available | frozen |
|------|-----------|--------|
| 买单挂单（Limit Buy） | -quote_frozen | +quote_frozen |
| 卖单挂单（Limit Sell） | -base_frozen | +base_frozen |
| 成交（买方） | +base_received | -quote_deducted |
| 成交（卖方） | +quote_received | -base_deducted |
| 撤单 | +解冻金额 | -解冻金额 |
| 市价买单 | -quote_frozen (预估) | +quote_frozen |

#### 保证金结构（Perp/Futures）

```
MarginAccount {
  accountId        : int64
  currency         : int32   // 保证金币种（USDT/USD/BTC）
  walletBalance    : int64   // 钱包余额
  unrealizedPnl    : int64   // 未实现盈亏
  marginBalance    : int64   // 保证金余额 = walletBalance + unrealizedPnl
  availableBalance : int64   // 可用余额 = marginBalance - initialMargin
  initialMargin    : int64   // 已用初始保证金
  maintenanceMargin: int64   // 维持保证金
  marginRatio      : int64   // 保证金率（固定精度）
}
```

#### 仓位结构（Perp/Futures）

```
Position {
  accountId      : int64
  symbolId       : int32
  side           : int8    // 1=Long, 2=Short
  quantity       : int64   // 持仓数量
  entryPrice     : int64   // 开仓均价
  unrealizedPnl  : int64   // 未实现盈亏（依据最新成交价计算）
  leverage       : int16   // 杠杆倍数
  liquidationPrice: int64  // 强平价格
  marginMode     : int8    // 1=逐仓, 2=全仓
}
```

### 5.3 订单生命周期状态机

```
                   [NewOrderRequest]
                          │
                    ┌─────▼──────┐
                    │  PENDING   │  柜台收到，风控校验中
                    └─────┬──────┘
              ┌───────────┼─────────────┐
         通过 │                         │ 拒绝
    ┌─────────▼──────┐         ┌────────▼──────────┐
    │   NEW (Active) │         │    REJECTED        │
    │   挂单在簿中    │         │  (终态，含拒绝原因) │
    └────┬──────┬────┘         └───────────────────┘
         │      │
  部分成交│      │撤单/改单/系统撤单
    ┌────▼────┐ │
    │PARTIALLY│ │
    │ FILLED  │ │
    └────┬────┘ │
         │      │
    全部成交│    ┌▼────────────────┐
    ┌────▼──┐   │   CANCELLED     │
    │FILLED │   │  (终态)          │
    │(终态) │   └─────────────────┘
    └───────┘
```

### 5.4 公共数据管理

柜台负责维护全局公共数据，所有节点状态一致（通过 Raft 同步）：

#### 交易对配置（SymbolConfig）

```
SymbolConfig {
  symbolId         : int32
  baseCurrency     : int32   // 基础货币ID
  quoteCurrency    : int32   // 计价货币ID
  symbolType       : int8    // 1=Spot, 2=Perp, 3=Futures
  status           : int8    // 0=Pre-Trading, 1=Trading, 2=Suspended, 3=Delisted
  pricePrecision   : int8    // 价格精度（小数位数）
  quantityPrecision: int8    // 数量精度
  priceTick        : int64   // 最小价格变动单位
  quantityStep     : int64   // 最小数量步长
  minOrderQty      : int64   // 最小下单量
  maxOrderQty      : int64   // 最大下单量
  maxLeverage      : int16   // 最大杠杆（Spot=1）
  makerFeeRate     : int32   // Maker 手续费率（固定精度，如 100 = 0.01%）
  takerFeeRate     : int32   // Taker 手续费率
  // Perp/Futures 附加字段
  contractSize     : int64   // 合约面值
  settleCurrency   : int32   // 结算货币
  deliveryTime     : int64   // 交割时间（Futures，0=永续）
}
```

### 5.5 订单 ID 生成策略

使用 **Snowflake 变体**，在单节点内保证单调递增：

```
64-bit Order ID:
┌──────────────────┬──────────┬─────────────────┐
│  42 bit 毫秒时间戳 │ 5 bit 节点ID │ 17 bit 序列号 │
└──────────────────┴──────────┴─────────────────┘
```

- 节点 ID 由 Aeron Cluster 成员 ID 决定（0/1/2）
- 序列号在同一毫秒内递增，最大支持 131072/ms = 131M/s

---

## 6. 撮合引擎详细设计

### 6.1 设计原则

- **每个交易对一个独立的 OrderBook 实例**，绑定独立的 CPU 核心
- 全内存操作，无任何磁盘 IO 在热路径
- 所有数据结构使用 **Agrona** 提供的无 GC 集合
- 撮合序列号全局单调递增，作为事件日志的主键

### 6.2 订单簿数据结构

#### 价格档位（PriceLevel）

```
PriceLevel {
  price       : int64
  totalQty    : int64       // 该价格档位总挂单量
  orderCount  : int32       // 该档位订单数量
  head        : OrderNode   // 链表头（时间优先）
  tail        : OrderNode   // 链表尾
}
```

#### 订单节点（OrderNode，预分配对象池）

```
OrderNode {
  orderId     : int64
  accountId   : int64
  price       : int64
  quantity    : int64
  filledQty   : int64
  leavesQty   : int64       // = quantity - filledQty
  orderType   : int8
  timeInForce : int8
  expireTime  : int64       // GTD 过期时间
  prev        : OrderNode
  next        : OrderNode
}
```

#### 订单簿结构

```
OrderBook {
  symbolId    : int32
  
  // 买盘：价格从高到低，使用 LongTreeMap (Agrona)
  bids        : LongTreeMap<PriceLevel>
  
  // 卖盘：价格从低到高，使用 LongTreeMap (Agrona)
  asks        : LongTreeMap<PriceLevel>
  
  // 快速查找：orderId → OrderNode，使用 Long2ObjectHashMap (Agrona)
  orderIndex  : Long2ObjectHashMap<OrderNode>
  
  // 统计
  lastTradePrice : int64
  lastTradeQty   : int64
  volume24h      : int64
  turnover24h    : int64
}
```

> **注：** `LongTreeMap` 使用红黑树实现，插入/删除/查找均为 O(log N)。实际工程中可用 `IntrusiveLinkedList` 优化同价位订单。

### 6.3 撮合算法

#### 标准限价单撮合（Price-Time Priority）

```
FUNCTION match(incomingOrder):
  IF incomingOrder.side == BUY:
    oppositeSide = asks                          // 对手盘是卖盘
    priceCondition = ask.price <= order.price    // 卖价 ≤ 买价时可成交
  ELSE:
    oppositeSide = bids
    priceCondition = bid.price >= order.price    // 买价 ≥ 卖价时可成交

  WHILE incomingOrder.leavesQty > 0 AND oppositeSide NOT EMPTY:
    bestLevel = oppositeSide.first()             // 取最优价格档位
    IF NOT priceCondition(bestLevel.price):
      BREAK                                      // 无法继续撮合
    
    FOR each order IN bestLevel (time priority):
      fillQty = min(incomingOrder.leavesQty, order.leavesQty)
      fillPrice = order.price                    // Maker 价格优先

      emit MatchResult(incomingOrder, order, fillPrice, fillQty)
      
      incomingOrder.leavesQty -= fillQty
      order.leavesQty -= fillQty

      IF order.leavesQty == 0:
        removeFromBook(order)                    // 移除全成交订单
      
      IF incomingOrder.leavesQty == 0:
        BREAK                                    // 入单已全部成交

  // 处理剩余数量
  IF incomingOrder.leavesQty > 0:
    SWITCH incomingOrder.timeInForce:
      GTC → addToBook(incomingOrder)             // 挂单等待
      IOC → cancel(incomingOrder)                // 立即撤销剩余
      FOK → rollback all fills                   // 全部回滚（预检查）
      PostOnly → cancel if filled any            // 若有成交则撤销
```

#### FOK 预检查

FOK 订单在实际撮合前先进行**流动性预检**：

```
FUNCTION canFillCompletely(order):
  remainingQty = order.quantity
  FOR each level IN oppositeSide (价格优先):
    IF NOT priceCondition(level.price): BREAK
    remainingQty -= min(remainingQty, level.totalQty)
    IF remainingQty == 0: RETURN true
  RETURN false
```

#### 市价单处理

- 市价买单：price 设为 `Long.MAX_VALUE`，匹配所有卖价
- 市价卖单：price 设为 `0`，匹配所有买价
- 市价单强制 IOC 语义，不允许挂单

### 6.4 Disruptor Pipeline 设计

```
Aeron IPC (Counter → ME)
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│              Matching Disruptor (RingBuffer 2^20)        │
│                                                         │
│  Producer: AeronInboundPublisher (单线程写入)            │
│                                                         │
│  ┌──────────────────────────────────────────────────┐   │
│  │  Stage 1: SequenceAssigner                        │   │
│  │  • 分配全局撮合序列号（单线程，保证严格有序）          │   │
│  └───────────────────────┬──────────────────────────┘   │
│                          │                              │
│  ┌───────────────────────▼──────────────────────────┐   │
│  │  Stage 2: MatchingHandler                         │   │
│  │  • 执行订单簿撮合逻辑                               │   │
│  │  • 生成 MatchResult 事件列表                        │   │
│  └───────────────────────┬──────────────────────────┘   │
│                          │                              │
│              ┌───────────┼─────────────┐               │
│              │           │             │               │
│  ┌───────────▼──┐  ┌─────▼──────┐  ┌──▼────────────┐  │
│  │JournalPublisher│ │ExecReporter│  │MarketDataPub  │  │
│  │写事件到 Aeron │  │回报→柜台    │  │推送行情变更    │  │
│  │IPC stream=5  │  │IPC stream=3│  │IPC stream=4   │  │
│  └──────────────┘  └────────────┘  └───────────────┘  │
└─────────────────────────────────────────────────────────┘
```

**关键设计点：**

- Stage 1（SequenceAssigner）和 Stage 2（MatchingHandler）是**单线程串行**的，保证撮合有序
- Stage 3 的三个 Handler 是**并行执行**的（Disruptor 菱形依赖），可同时写日志、回报、推行情
- 每个交易对的 MatchingHandler 绑定独立线程，多交易对之间完全并行

### 6.5 订单簿快照与深度聚合

```
OrderBook 完整快照（用于恢复）：
  snapshots/
    {symbolId}_{sequenceNo}.snapshot    // 定期生成

深度数据（用于推送）：
  Level-2 Depth: Top N 档买卖盘聚合
    bids: [(price, qty), ...]  按价格降序，取前 N 档
    asks: [(price, qty), ...]  按价格升序，取前 N 档
  
  差量更新推送：
    仅推送有变化的价格档位，减少推送数据量
```

---

## 7. 推送服务详细设计

### 7.1 数据流

```
撮合引擎 Aeron IPC (stream=4)
          │
          ▼
┌─────────────────────────────────────────────────────────┐
│                    Push Dispatcher                       │
│                                                         │
│  Aeron Subscriber (BusySpin) → EventClassifier          │
│                                        │                │
│          ┌─────────────────────────────┤               │
│          │         │                   │               │
│  ┌───────▼──┐  ┌───▼──────┐  ┌────────▼────────┐      │
│  │DepthQueue│  │TradeQueue│  │TickerAggregator │      │
│  │差量深度  │  │逐笔成交  │  │聚合最新价格/量   │      │
│  └───────┬──┘  └───┬──────┘  └────────┬────────┘      │
│          │         │                   │               │
│  ┌───────▼─────────▼───────────────────▼────────────┐  │
│  │           Netty WebSocket Broadcaster              │  │
│  │  • 按订阅过滤（symbolId + channel）                 │  │
│  │  • 批量聚合（1ms 窗口合并多个更新）                   │  │
│  │  • JSON 序列化（对外）                               │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 7.2 推送频道定义

| 频道 | 触发条件 | 数据内容 |
|------|---------|---------|
| `depth@{symbol}@{level}` | 订单簿变化 | 全量/差量深度（5/10/20档） |
| `trade@{symbol}` | 每笔成交 | 成交价、量、方向、时间 |
| `ticker@{symbol}` | 每笔成交 | 最新价、24h涨跌幅、量 |
| `order@{account}` | 个人订单状态变更 | ExecutionReport |
| `position@{account}` | 仓位变更 | PositionUpdate |
| `balance@{account}` | 余额变更 | BalanceUpdate |
| `kline@{symbol}@{interval}` | 定时聚合 | OHLCV K线数据 |

### 7.3 订阅管理

```
SubscriptionRegistry {
  // symbolId → Set<WebSocketSession>  (公共行情订阅)
  depthSubscribers  : Int2ObjectHashMap<Set<Session>>
  tradeSubscribers  : Int2ObjectHashMap<Set<Session>>
  tickerSubscribers : Int2ObjectHashMap<Set<Session>>
  
  // accountId → Set<WebSocketSession>  (私有推送)
  privateSubscribers: Long2ObjectHashMap<Set<Session>>
  
  // 订阅/取消订阅操作通过 Disruptor 异步处理，避免并发问题
}
```

---

## 8. 高可用方案 (Aeron Cluster)

### 8.1 Aeron Cluster 原理

Aeron Cluster 基于 **Raft 共识协议**，将服务实现为**确定性状态机（Deterministic State Machine）**：

- 所有**输入消息**通过 Raft 日志有序复制到所有节点
- 各节点按相同顺序执行相同输入，得到相同状态
- Leader 故障时自动选主，其他节点接管服务，无需外部协调

### 8.2 ClusteredService 实现要求

柜台+撮合引擎需实现 `ClusteredService` 接口：

```
interface ClusteredService {
  onStart(cluster, image)        // 节点启动/状态恢复完成时调用
  onSessionOpen(session, ...)    // 客户端（Gateway）连接建立
  onSessionMessage(session, buf, ...) // 处理入站消息（核心）
  onSessionClose(session, ...)   // 客户端连接断开
  onTimerEvent(correlationId, timestamp)  // 定时任务回调
  onTakeSnapshot(publication)    // 触发状态快照
  onRoleChange(role)             // Leader/Follower 角色变更
}
```

**确定性要求（关键）：**
- 禁止在 `onSessionMessage` 中使用 `System.currentTimeMillis()` — 使用 Cluster 提供的 `cluster.time()`
- 禁止使用 `Random` — 使用确定性伪随机数
- 禁止多线程并发修改状态 — 所有状态变更在单一调度线程
- 禁止外部 IO（数据库读写等）在热路径

### 8.3 节点部署规划

```
Node 0 (Leader 候选)
  服务器: 物理机 A
  Aeron Archive: localhost:8010
  Cluster Ingress: 0.0.0.0:9010
  Cluster Log: 0.0.0.0:9011

Node 1 (Follower)
  服务器: 物理机 B
  Aeron Archive: localhost:8010
  Cluster Ingress: 0.0.0.0:9010
  Cluster Log: 0.0.0.0:9011

Node 2 (Follower)
  服务器: 物理机 C
  Aeron Archive: localhost:8010
  Cluster Ingress: 0.0.0.0:9010
  Cluster Log: 0.0.0.0:9011
```

### 8.4 快照策略

- 每隔 **N 万条日志**或**5 分钟**触发一次快照
- 快照序列化内容：全量账户余额、仓位、订单簿状态、公共配置
- 快照写入 **Aeron Archive**（基于 MappedFile 的高性能归档）
- 节点重启时：加载最新快照 + 重放快照后的增量日志 → 恢复完整状态

### 8.5 Gateway 与 Cluster 的交互

```
Gateway 集群 → Aeron Cluster Ingress (负载均衡)
                       │
              ┌────────▼─────────┐
              │  Cluster Ingress │  将请求路由给当前 Leader
              └────────┬─────────┘
                       │ (Leader Only)
              ┌────────▼─────────┐
              │  ClusteredService│  执行业务逻辑
              └────────┬─────────┘
                       │ (Cluster Egress)
              回报给发起请求的 Gateway Session
```

- Gateway 维护与所有 Cluster 节点的连接，自动识别 Leader
- 请求只发给 Leader，Leader 处理后通过 Egress 回报给原 Gateway

---

## 9. 持久化与恢复方案

### 9.1 事件日志（Write-Ahead Log）

撮合引擎将所有**成交事件**异步写入 Journal Service：

```
Journal Event 格式（二进制追加写）：
┌──────────────────────────────────────────────────┐
│ MagicNumber(4) │ Length(4) │ SequenceNo(8)        │
│ Timestamp(8)   │ EventType(1) │ Payload(N)         │
│ Checksum(4)    │                                  │
└──────────────────────────────────────────────────┘
```

事件类型包括：
- `ORDER_NEW`：新订单入簿
- `ORDER_CANCEL`：订单撤销
- `TRADE`：成交事件
- `SNAPSHOT_REF`：快照引用（指向某一快照文件）

### 9.2 故障恢复流程

```
启动恢复流程：
  1. 从 Aeron Archive 加载最近一次 Cluster Snapshot
  2. 恢复 Counter Service 状态（账户/仓位/订单）
  3. 恢复 Matching Engine 状态（从快照中的订单簿）
  4. Aeron Cluster 自动重放快照后的 Raft 日志
  5. 服务就绪，开始处理新请求

正常模式下 Journal Service 的额外作用：
  • 提供给风控/监控/审计系统实时消费
  • 用于 T+1 结算、账单对账
  • 支持冷备份归档到对象存储（S3/OSS）
```

### 9.3 Journal 写入流程

```
撮合引擎 Aeron IPC (stream=5)
         │
         ▼ (异步，不阻塞撮合热路径)
┌──────────────────────────────────┐
│         Journal Service           │
│                                  │
│  Aeron Subscriber                │
│       │                          │
│  BatchAccumulator (1ms 批次)     │
│       │                          │
│  MappedFileWriter                │
│  • 按日期/序列号分割文件            │
│  • fsync 策略：每批或每 10ms      │
│  • 文件大小上限：1GB/文件          │
└──────────────────────────────────┘
```

---

## 10. 风控模块设计

### 10.1 风控层次

```
Layer 1: Gateway 层（前置）
  • API 限流（每账户每秒请求数）
  • IP 限流（防 DDoS）
  • 消息大小校验

Layer 2: Counter 层（业务风控）
  • 余额/保证金充足性校验
  • 仓位上限校验（per-account, per-symbol）
  • 订单价格合理性校验（防异常价格冲击）
  • 订单数量合理性校验
  • 撤单频率限制

Layer 3: 撮合层（撮合保护）
  • 价格笼子（Price Band）：成交价不得偏离最优价 X%
  • 市价单滑点保护：市价单深度不足时部分拒绝
```

### 10.2 余额校验逻辑

#### Spot 限价买单

```
required_frozen = price × quantity + fee_estimate
CHECK: account.available[quoteCurrency] >= required_frozen
ACTION: account.available[quoteCurrency] -= required_frozen
        account.frozen[quoteCurrency] += required_frozen
```

#### Spot 限价卖单

```
required_frozen = quantity
CHECK: account.available[baseCurrency] >= required_frozen
ACTION: account.available[baseCurrency] -= required_frozen
        account.frozen[baseCurrency] += required_frozen
```

#### Perp/Futures 开仓

```
initial_margin = (price × quantity × contractSize) / leverage
fee_estimate = price × quantity × contractSize × takerFeeRate
required = initial_margin + fee_estimate
CHECK: account.availableBalance[marginCurrency] >= required
ACTION: account.availableBalance -= required
        account.initialMargin += initial_margin
```

### 10.3 价格笼子（Price Band）

```
// 防止异常订单价格冲击市场
FUNCTION validatePrice(order):
  referencePrice = lastTradePrice OR markPrice
  IF order.orderType == LIMIT:
    upperBound = referencePrice × (1 + priceBandRatio)
    lowerBound = referencePrice × (1 - priceBandRatio)
    CHECK: lowerBound <= order.price <= upperBound
    // priceBandRatio 可配置，如 Spot=10%, Futures=5%
```

---

## 11. 数据模型设计

### 11.1 内存数据结构总览

所有数据结构使用 **Agrona** 的无装箱集合，避免 GC：

```
Counter Service 内存结构：
  accountBalances   : Long2ObjectHashMap<AccountBalance[]>  // accountId → 各资产余额
  positions         : Long2ObjectHashMap<Position[]>        // accountId → 各交易对仓位
  openOrders        : Long2ObjectHashMap<OrderState>        // orderId → 订单状态
  symbolConfigs     : Int2ObjectHashMap<SymbolConfig>       // symbolId → 交易对配置
  sequenceIdGen     : AtomicLong                            // 订单ID生成器（实际为单线程）

Matching Engine 内存结构：
  orderBooks        : Int2ObjectHashMap<OrderBook>          // symbolId → 订单簿
  sequenceNo        : long                                  // 全局撮合序列号
  objectPool        : OrderNodePool                         // 预分配对象池
```

### 11.2 精度规范

**所有价格和数量使用 long 存储，精度因子存于 SymbolConfig：**

```
示例：BTC/USDT Spot
  pricePrecision   = 2      // 最小单位 0.01 USDT
  quantityPrecision = 6     // 最小单位 0.000001 BTC
  
存储：
  price  = 50000.25 USDT → 存储 long: 5000025  (精度 0.01)
  quantity = 0.123456 BTC → 存储 long: 123456  (精度 0.000001)

计算：
  成交金额 = price_long × quantity_long / 10^(pricePrecision + quantityPrecision)
           = 5000025 × 123456 / 10^8
           = 617,283,086,400 / 100,000,000
           = 6172.830864 USDT
```

---

## 12. 性能优化策略

### 12.1 CPU 级优化

| 优化点 | 具体措施 | 预期收益 |
|--------|---------|---------|
| CPU 亲和性 | 撮合线程绑定独立物理核（避免共享 L1/L2 cache） | 减少 cache miss |
| NUMA 感知 | 内存分配在本地 NUMA 节点 | 减少内存访问延迟 |
| 大页内存 | JVM 使用 `-XX:+UseLargePages`（2MB 大页） | 减少 TLB miss |
| 忙轮询 | 热路径线程使用 `BusySpinIdleStrategy` | 避免线程调度延迟 |
| 线程隔离 | 使用 `isolcpus` 内核参数隔离 CPU 核心 | 消除 OS 调度干扰 |

### 12.2 JVM 级优化

```
JVM 启动参数（生产环境）：

-server
-Xms64g -Xmx64g                         # 堆大小固定，避免扩容 GC
-XX:+UseZGC                             # ZGC：亚毫秒 GC 停顿
-XX:MaxGCPauseMillis=1
-XX:+AlwaysPreTouch                     # 启动时预触碰内存，避免延迟分配
-XX:+UseLargePages
-XX:+UseTransparentHugePages
-XX:+DisableExplicitGC                  # 禁止显式 GC
-XX:+UnlockExperimentalVMOptions
-XX:+UseJVMCICompiler                   # Graal JIT
--add-opens java.base/sun.nio.ch=ALL-UNNAMED  # Aeron 需要
-Daeron.threading.mode=DEDICATED        # Aeron 专用线程模式
-Daeron.dir=/dev/shm/aeron              # 使用内存文件系统
```

### 12.3 零 GC 热路径设计

```
策略：
  1. 对象预分配：系统启动时预分配所有 OrderNode 到对象池
  2. 环形复用：OrderNode 从池中取用，成交/撤销后归还
  3. 避免装箱：所有集合使用 Agrona 的 primitive 版本
  4. SBE 零拷贝：消息直接在 DirectBuffer 上编解码，无中间对象
  5. ThreadLocal 复用：临时对象（如 MatchResult 列表）用 ThreadLocal 缓存

监控：
  使用 jHiccup 测量 JVM 停顿
  使用 Agrona SystemCounters 监控 GC 压力
```

### 12.4 Disruptor 调优

```
RingBuffer 大小：
  选 2 的幂次方，撮合引擎用 2^20 = 1,048,576
  过大浪费内存，过小在峰值时背压

Wait Strategy：
  Producer/Consumer 均使用 BusySpin → 最低延迟
  
Batch Size：
  Consumer 每次批量消费多个事件，减少 volatile 读次数

False Sharing 防护：
  Disruptor 已内置 @Contended padding，确保 Sequence 对象独占 Cache Line
```

### 12.5 Aeron 调优

```
Media Driver 配置：
  aeron.term.buffer.length=67108864    # Term Buffer 64MB（每个 stream）
  aeron.ipc.term.buffer.length=33554432  # IPC Term Buffer 32MB
  aeron.socket.so_sndbuf=2097152       # UDP 发送缓冲 2MB
  aeron.socket.so_rcvbuf=2097152       # UDP 接收缓冲 2MB
  aeron.rcv.initial.window.length=2097152

Publication 配置：
  aeron.dir=/dev/shm/aeron             # 使用 tmpfs/hugetlbfs
  
Conductor Thread:
  绑定独立 CPU 核心，不与业务线程共享
```

---

## 13. 部署架构

### 13.1 单机硬件配置（推荐）

```
CPU: 双路 Intel Xeon Scalable 或 AMD EPYC
     >= 32 核心 (需要隔离核心用于撮合引擎)
     
内存: >= 256GB DDR5 ECC，支持大页
      NUMA: 2 节点，撮合进程绑定 NUMA 节点 0

存储: NVMe SSD RAID（用于 Journal），>= 4TB
      /dev/shm 分区 >= 16GB（用于 Aeron IPC）

网络: 25GbE 或 100GbE（Cluster 节点间）
      RDMA (RoCE/InfiniBand) 可选，进一步降低跨节点延迟

OS: Linux (RHEL/CentOS/Ubuntu Server)
    内核参数：isolcpus, nohz_full, rcu_nocbs
    网络：IRQ 亲和性调优，网卡多队列绑核
```

### 13.2 生产集群规划

```
Region A (主区域)
├── Gateway Cluster (2台，负载均衡)
│     gateway-01: Netty + Aeron UDP Client
│     gateway-02: Netty + Aeron UDP Client
│
├── Aeron Cluster (3台，Raft)
│     cluster-01: Counter + MatchEngine (Leader 候选)
│     cluster-02: Counter + MatchEngine (Follower)
│     cluster-03: Counter + MatchEngine (Follower)
│
├── Push Service Cluster (2台)
│     push-01: Aeron Sub + WebSocket Server
│     push-02: Aeron Sub + WebSocket Server
│
└── Journal Service (1台，独立)
      journal-01: 事件日志写入 + 归档

Region B (灾备区域)
└── 异步复制 Journal 数据，用于灾难恢复
```

### 13.3 核心绑定规划（以 32 核机器为例）

```
Core 0-1:   OS + 系统进程（isolcpus 排除）
Core 2-3:   Aeron Media Driver (Conductor + Sender + Receiver)
Core 4:     Counter Service Disruptor Worker
Core 5-16:  Matching Engine (每交易对 1 核，支持 12 个交易对)
Core 17-18: Journal Service
Core 19-20: Push Service
Core 21-31: Gateway Netty Worker Thread Pool
```

---

## 14. 项目工程结构

```
trading-platform/
├── pom.xml                              # Maven 父 POM
│
├── common/                              # 共享基础库（无业务逻辑）
│   ├── common-sbe/                      # SBE 消息定义 & 生成代码
│   │   └── src/main/resources/sbe/
│   │       ├── MessageHeader.xml
│   │       ├── ClientMessages.xml       # 客户端入站消息
│   │       ├── ExecutionMessages.xml    # 执行回报消息
│   │       ├── InternalMessages.xml     # 内部消息
│   │       └── MarketDataMessages.xml   # 行情消息
│   ├── common-model/                    # 领域模型对象
│   │   └── src/main/java/
│   │       ├── model/Order.java
│   │       ├── model/Position.java
│   │       ├── model/AccountBalance.java
│   │       └── model/SymbolConfig.java
│   └── common-util/                     # 工具类
│       └── src/main/java/
│           ├── util/PriceUtil.java       # 精度计算
│           ├── util/SnowflakeIdGen.java  # 订单 ID 生成
│           ├── util/TimeUtil.java        # 纳秒时间
│           └── util/ObjectPool.java      # 通用对象池
│
├── gateway-service/                     # 接入层
│   └── src/main/java/
│       ├── GatewayMain.java
│       ├── netty/
│       │   ├── HttpServerHandler.java   # REST 接入
│       │   └── WebSocketHandler.java    # WebSocket 接入
│       ├── auth/
│       │   ├── JwtAuthenticator.java
│       │   └── HmacAuthenticator.java
│       ├── ratelimit/
│       │   └── TokenBucketRateLimiter.java
│       └── aeron/
│           ├── AeronClusterClient.java  # 连接 Cluster Ingress
│           └── GatewayEgressListener.java  # 接收 Cluster 回报
│
├── counter-service/                     # 柜台服务
│   └── src/main/java/
│       ├── CounterServiceMain.java
│       ├── cluster/
│       │   └── CounterClusteredService.java  # 实现 ClusteredService
│       ├── account/
│       │   ├── AccountManager.java      # 余额管理
│       │   └── PositionManager.java     # 仓位管理
│       ├── order/
│       │   └── OrderStateManager.java   # 订单状态管理
│       ├── risk/
│       │   ├── BalanceRiskChecker.java  # 余额风控
│       │   ├── PositionRiskChecker.java # 仓位风控
│       │   └── PriceBandChecker.java    # 价格笼子
│       ├── symbol/
│       │   └── SymbolConfigManager.java # 交易对配置管理
│       ├── fee/
│       │   └── FeeCalculator.java       # 手续费计算
│       └── disruptor/
│           ├── InboundDisruptor.java    # Disruptor 配置
│           ├── AuthHandler.java
│           ├── SymbolHandler.java
│           ├── RiskHandler.java
│           ├── FreezeHandler.java
│           └── RouteHandler.java
│
├── matching-engine/                     # 撮合引擎
│   └── src/main/java/
│       ├── MatchingEngineMain.java
│       ├── orderbook/
│       │   ├── OrderBook.java           # 订单簿核心
│       │   ├── PriceLevel.java          # 价格档位
│       │   ├── OrderNode.java           # 订单节点
│       │   └── OrderNodePool.java       # 对象池
│       ├── matcher/
│       │   ├── OrderMatcher.java        # 撮合逻辑
│       │   ├── LimitOrderMatcher.java
│       │   └── MarketOrderMatcher.java
│       ├── disruptor/
│       │   ├── MatchingDisruptor.java   # Disruptor 配置
│       │   ├── SequenceAssignHandler.java
│       │   ├── MatchingHandler.java
│       │   ├── JournalPublishHandler.java
│       │   ├── ExecutionReportHandler.java
│       │   └── MarketDataPublishHandler.java
│       └── aeron/
│           ├── MatchingEngineAeronConfig.java
│           └── InboundOrderSubscriber.java
│
├── push-service/                        # 推送服务
│   └── src/main/java/
│       ├── PushServiceMain.java
│       ├── aeron/
│       │   └── MarketDataSubscriber.java
│       ├── dispatcher/
│       │   ├── DepthDispatcher.java
│       │   ├── TradeDispatcher.java
│       │   └── TickerAggregator.java
│       ├── subscription/
│       │   └── SubscriptionRegistry.java
│       └── netty/
│           └── WebSocketPushHandler.java
│
├── journal-service/                     # 持久化服务
│   └── src/main/java/
│       ├── JournalServiceMain.java
│       ├── aeron/
│       │   └── JournalEventSubscriber.java
│       ├── writer/
│       │   ├── MappedFileJournalWriter.java
│       │   └── JournalRotationManager.java
│       └── recovery/
│           └── JournalReplayService.java
│
└── cluster-bootstrap/                   # Aeron Cluster 启动配置
    └── src/main/java/
        ├── ClusterNodeMain.java
        └── ClusterConfig.java
```

---

## 15. 关键流程时序图

### 15.1 正常下单流程

```
Client          Gateway         Counter(Cluster)    MatchEngine      Client(回报)
  │                │                   │                │                │
  │─NewOrder(REST)─▶                   │                │                │
  │                │─SBE编码─▶         │                │                │
  │                │   Aeron UDP       │                │                │
  │                │──────────────────▶│                │                │
  │                │          [Raft日志复制到所有节点]   │                │
  │                │                   │─RiskCheck      │                │
  │                │                   │─FreezeBalance  │                │
  │                │                   │─AssignOrderId  │                │
  │                │                   │─Aeron IPC─────▶│                │
  │                │                   │         SequenceAssign         │
  │                │                   │         Match()               │
  │                │                   │◀──MatchResult──│                │
  │                │                   │ UpdatePosition │                │
  │                │                   │ UpdateBalance  │                │
  │                │◀──ExecReport──────│                │                │
  │◀──HTTP 200─────│                   │                │                │
  │                │                   │                │──ExecReport──▶│
  │                │                   │                │  (via Push)    │
```

### 15.2 撤单流程

```
Client          Gateway         Counter             MatchEngine
  │                │                │                   │
  │─CancelOrder────▶                │                   │
  │                │─Aeron UDP─────▶│                   │
  │                │           OrderStateCheck          │
  │                │           (订单是否存在且可撤)        │
  │                │                │─Aeron IPC────────▶│
  │                │                │             RemoveFromBook        │
  │                │                │◀─CancelConfirm────│
  │                │           UnfreezeBalance          │
  │                │◀─ExecReport(Cancel)                │
  │◀─Response──────│                │                   │
```

### 15.3 节点故障恢复流程

```
Node-0(Leader)   Node-1(Follower)   Node-2(Follower)   Gateway
     │                  │                  │               │
  [CRASH]               │                  │               │
     ×                  │                  │               │
                   [Election Timer]        │               │
                   [Vote Request] ────────▶│               │
                        │◀──────[Vote]─────│               │
                   [Become Leader]         │               │
                        │                  │               │
                 [Load Snapshot]           │               │
                 [Replay Log]              │               │
                 [Service Ready]           │               │
                        │◀────────────────────────────────│
                        │  (Gateway 重连，新 Leader 响应)   │
```

---

## 16. 里程碑计划

### Phase 1：基础框架（第 1-2 周）

- [ ] 定义 SBE 消息 Schema，生成编解码器
- [ ] 搭建 Maven 多模块项目骨架
- [ ] 实现 common-util（ID 生成、精度工具、对象池）
- [ ] 实现基础 Aeron IPC 收发 Demo（验证环境）
- [ ] 实现基础 Disruptor Pipeline Demo

**验证目标：** 单链路 Aeron IPC → Disruptor → Aeron IPC，延迟 < 1μs

### Phase 2：撮合引擎核心（第 3-4 周）

- [ ] 实现 OrderBook 数据结构（LongTreeMap + 链表）
- [ ] 实现 Limit/Market/IOC/FOK/PostOnly 撮合逻辑
- [ ] 实现撮合 Disruptor Pipeline（3段流水线）
- [ ] 单元测试：边界 case 覆盖（空簿、全成交、部分成交等）
- [ ] 性能基准测试：单交易对 TPS

**验证目标：** 单交易对 > 500K orders/sec，撮合延迟 P99 < 5μs

### Phase 3：柜台服务（第 5-6 周）

- [ ] 实现账户余额管理（Spot + 保证金）
- [ ] 实现仓位管理（Perp/Futures）
- [ ] 实现风控模块（余额校验、价格笼子）
- [ ] 实现订单状态机
- [ ] 实现手续费计算
- [ ] 集成 Counter → MatchEngine → Counter 完整回路

**验证目标：** 完整下单→撮合→回报链路 P99 < 10μs

### Phase 4：Gateway & Push（第 7-8 周）

- [ ] 实现 Netty HTTP/WebSocket Gateway
- [ ] 实现 JWT/HMAC 鉴权
- [ ] 实现令牌桶限流
- [ ] 实现 Push Service（深度/成交/Ticker 推送）
- [ ] 实现 Journal Service

**验证目标：** 端到端客户端 → 交易所 → 客户端，P99 < 1ms

### Phase 5：高可用（第 9-10 周）

- [ ] 实现 Aeron Cluster ClusteredService 集成
- [ ] 实现快照（Snapshot）序列化/反序列化
- [ ] 实现故障切换测试（Kill Leader，验证自动切主）
- [ ] 实现 Journal 回放恢复

**验证目标：** Leader 故障后 < 3 秒完成切主，无数据丢失

### Phase 6：集成测试 & 压测（第 11-12 周）

- [ ] 全链路集成测试（多账户、多交易对并发）
- [ ] 压力测试（目标 1M TPS）
- [ ] 延迟分布测量（HdrHistogram）
- [ ] 故障注入测试（网络分区、进程 Kill、磁盘满）
- [ ] 监控指标接入（Prometheus + Grafana）

---

## 附录：关键依赖版本

```xml
<properties>
  <aeron.version>1.44.1</aeron.version>
  <disruptor.version>4.0.0</disruptor.version>
  <agrona.version>1.21.2</agrona.version>
  <sbe.version>1.30.0</sbe.version>
  <netty.version>4.1.108.Final</netty.version>
  <affinity.version>3.23.3</affinity.version>
  <hdrhistogram.version>2.2.2</hdrhistogram.version>
  <java.version>21</java.version>
</properties>

<!-- Aeron (含 Cluster) -->
<dependency>
  <groupId>io.aeron</groupId>
  <artifactId>aeron-all</artifactId>
  <version>${aeron.version}</version>
</dependency>

<!-- LMAX Disruptor -->
<dependency>
  <groupId>com.lmax</groupId>
  <artifactId>disruptor</artifactId>
  <version>${disruptor.version}</version>
</dependency>

<!-- Agrona 无锁数据结构 -->
<dependency>
  <groupId>org.agrona</groupId>
  <artifactId>agrona</artifactId>
  <version>${agrona.version}</version>
</dependency>

<!-- SBE 编解码 -->
<dependency>
  <groupId>uk.co.real-logic</groupId>
  <artifactId>sbe-all</artifactId>
  <version>${sbe.version}</version>
</dependency>

<!-- Netty -->
<dependency>
  <groupId>io.netty</groupId>
  <artifactId>netty-all</artifactId>
  <version>${netty.version}</version>
</dependency>

<!-- CPU 亲和性绑定 -->
<dependency>
  <groupId>net.openhft</groupId>
  <artifactId>Java-Thread-Affinity</artifactId>
  <version>${affinity.version}</version>
</dependency>

<!-- 延迟直方图 -->
<dependency>
  <groupId>org.hdrhistogram</groupId>
  <artifactId>HdrHistogram</artifactId>
  <version>${hdrhistogram.version}</version>
</dependency>
```
