# Phase 3 柜台服务实现 — Part 1：总览与领域模型

> **目标：** 定义柜台服务的核心领域模型：账户余额、保证金账户、仓位、订单状态、
> 交易对配置，这些模型是后续 AccountManager / PositionManager / 风控模块的数据基础。
>
> **前置条件：** Phase 2 完成，撮合引擎全部 69 个测试通过  
> **本节验证目标：** 领域模型单元测试通过，精度计算无误

---

## 目录

1. [Phase 3 总览](#1-phase-3-总览)
2. [AccountBalance（现货余额）](#2-accountbalance现货余额)
3. [MarginAccount（保证金账户）](#3-marginaccount保证金账户)
4. [Position（合约仓位）](#4-position合约仓位)
5. [OrderState（订单状态）](#5-orderstate订单状态)
6. [SymbolConfig（交易对配置）](#6-symbolconfig交易对配置)
7. [领域模型单元测试](#7-领域模型单元测试)

---

## 1. Phase 3 总览

### 1.1 本阶段目标

Phase 3 对应设计文档第 5 节"柜台服务详细设计"，分 5 个 Part 依次实现：

| Part | 内容 | 验证目标 |
|------|------|---------|
| **Part 1**（本文） | 领域模型定义 | 精度计算、状态流转单元测试 |
| Part 2 | AccountManager + PositionManager | 余额/仓位 CRUD 测试通过 |
| Part 3 | 风控模块 + FeeCalculator | 拒绝场景 100% 覆盖 |
| Part 4 | 柜台 Disruptor Pipeline（5 段 Handler）+ ExecutionReportProcessor | Pipeline 联调通过 |
| Part 5 | ClusteredService 集成 + 全量单测 + 验收清单 | 完整链路 P99 < 10μs |

### 1.2 新增文件结构

```
counter-service/src/main/java/com/trading/counter/
├── model/
│   ├── AccountBalance.java       ← 现货余额（本 Part）
│   ├── MarginAccount.java        ← 保证金账户（本 Part）
│   ├── Position.java             ← 合约仓位（本 Part）
│   ├── OrderState.java           ← 订单运行时状态（本 Part）
│   └── SymbolConfig.java         ← 交易对配置（本 Part）
├── account/
│   ├── AccountManager.java       ← Part 2
│   └── PositionManager.java      ← Part 2
├── risk/
│   ├── BalanceRiskChecker.java   ← Part 3
│   ├── PositionRiskChecker.java  ← Part 3
│   └── PriceBandChecker.java     ← Part 3
├── fee/
│   └── FeeCalculator.java        ← Part 3
├── symbol/
│   └── SymbolConfigManager.java  ← Part 3
├── disruptor/
│   ├── CounterEvent.java         ← Part 4
│   ├── AuthHandler.java          ← Part 4
│   ├── SymbolHandler.java        ← Part 4
│   ├── RiskHandler.java          ← Part 4
│   ├── FreezeHandler.java        ← Part 4
│   ├── RouteHandler.java         ← Part 4
│   └── InboundDisruptor.java     ← Part 4
├── processor/
│   └── ExecutionReportProcessor.java  ← Part 4
└── cluster/
    └── CounterClusteredService.java   ← Part 5

counter-service/src/test/java/com/trading/counter/
├── model/
│   └── DomainModelTest.java      ← 本 Part
├── account/
│   ├── AccountManagerTest.java   ← Part 2
│   └── PositionManagerTest.java  ← Part 2
├── risk/
│   └── RiskCheckerTest.java      ← Part 3
└── disruptor/
    └── CounterPipelineTest.java  ← Part 4
```

### 1.3 柜台服务架构回顾

```
Cluster Ingress (来自 Gateway，经 Raft 日志复制)
        │
        ▼
┌───────────────────────────────────────────────────────────┐
│                   Counter Service                          │
│                                                           │
│  Inbound Disruptor (RingBuffer 2^20):                     │
│  [AuthHandler] → [SymbolHandler] → [RiskHandler]          │
│               → [FreezeHandler] → [RouteHandler]          │
│                                         │                 │
│                              Aeron IPC stream=2           │
│                              → Matching Engine            │
│                                                           │
│  ExecutionReportProcessor (订阅 stream=3):                 │
│  MatchResult → updatePosition/Balance → ExecutionReport   │
│             → Cluster Egress → Gateway → Client           │
└───────────────────────────────────────────────────────────┘
```

### 1.4 counter-service POM 补充依赖

在 `counter-service/pom.xml` 中确认以下依赖（基于根 POM 统一版本管理）：

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

    <artifactId>counter-service</artifactId>
    <name>Counter Service</name>

    <dependencies>
        <dependency>
            <groupId>com.trading</groupId>
            <artifactId>common-sbe</artifactId>
        </dependency>
        <dependency>
            <groupId>com.trading</groupId>
            <artifactId>common-model</artifactId>
        </dependency>
        <dependency>
            <groupId>com.trading</groupId>
            <artifactId>common-util</artifactId>
        </dependency>
        <dependency>
            <groupId>io.aeron</groupId>
            <artifactId>aeron-all</artifactId>
        </dependency>
        <dependency>
            <groupId>com.lmax</groupId>
            <artifactId>disruptor</artifactId>
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

## 2. AccountBalance（现货余额）

现货账户对每种资产维护三个字段：可用余额、冻结余额、总余额。

文件：`counter-service/src/main/java/com/trading/counter/model/AccountBalance.java`

```java
package com.trading.counter.model;

/**
 * 现货账户单资产余额。
 *
 * <p>余额规则：
 * <pre>
 *   total = available + frozen
 *
 *   挂限价买单：available -= quote_frozen;  frozen += quote_frozen
 *   挂限价卖单：available -= base_frozen;   frozen += base_frozen
 *   买单成交：  available += base_received; frozen -= quote_deducted
 *   卖单成交：  available += quote_received; frozen -= base_deducted
 *   撤单：      available += 解冻金额;       frozen -= 解冻金额
 * </pre>
 *
 * <p>所有数值为固定精度整数，精度因子由 {@link SymbolConfig} 定义。
 *
 * @author Reln Ding
 */
public final class AccountBalance {

    /** 账户 ID */
    public final long accountId;

    /** 资产 ID（如 BTC=1, USDT=2） */
    public final int assetId;

    /** 可用余额（可下单的部分）*/
    private long available;

    /** 冻结余额（挂单占用，不可再用于下单）*/
    private long frozen;

    public AccountBalance(final long accountId, final int assetId) {
        this.accountId = accountId;
        this.assetId   = assetId;
        this.available = 0L;
        this.frozen    = 0L;
    }

    public AccountBalance(final long accountId, final int assetId,
                          final long available, final long frozen) {
        this.accountId = accountId;
        this.assetId   = assetId;
        this.available = available;
        this.frozen    = frozen;
    }

    // ---- 查询 ----

    public long getAvailable() { return available; }
    public long getFrozen()    { return frozen;    }
    public long getTotal()     { return available + frozen; }

    // ---- 业务操作（由 AccountManager 调用，不直接对外暴露）----

    /**
     * 充值（充值 / 划入）：增加可用余额。
     *
     * @param amount 充值金额（必须 > 0）
     * @throws IllegalArgumentException amount <= 0
     */
    public void deposit(final long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("deposit amount must be > 0, got: " + amount);
        }
        available += amount;
    }

    /**
     * 提现（划出）：减少可用余额。
     *
     * @param amount 提现金额（必须 > 0 且 <= available）
     * @return true=成功，false=余额不足
     */
    public boolean withdraw(final long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("withdraw amount must be > 0, got: " + amount);
        }
        if (available < amount) {
            return false;
        }
        available -= amount;
        return true;
    }

    /**
     * 冻结资金（挂单时调用）：available → frozen。
     *
     * @param amount 冻结金额
     * @return true=成功，false=可用余额不足
     */
    public boolean freeze(final long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("freeze amount must be > 0, got: " + amount);
        }
        if (available < amount) {
            return false;
        }
        available -= amount;
        frozen    += amount;
        return true;
    }

    /**
     * 解冻资金（撤单时调用）：frozen → available。
     *
     * @param amount 解冻金额
     * @throws IllegalStateException 解冻金额超过冻结余额（数据不一致）
     */
    public void unfreeze(final long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("unfreeze amount must be > 0, got: " + amount);
        }
        if (frozen < amount) {
            throw new IllegalStateException(
                "unfreeze amount " + amount + " exceeds frozen " + frozen
                + " for account=" + accountId + " asset=" + assetId);
        }
        frozen    -= amount;
        available += amount;
    }

    /**
     * 成交扣减（从冻结余额中划出）：frozen -= deductAmount，available += creditAmount。
     *
     * <p>用于成交结算：
     * <ul>
     *   <li>买方：frozen -= quote_cost；available += base_received</li>
     *   <li>卖方：frozen -= base_sold；available += quote_received</li>
     * </ul>
     *
     * @param deductFromFrozen 从冻结余额扣除的金额
     * @param creditToAvailable 增加到可用余额的金额
     * @throws IllegalStateException deductFromFrozen 超过冻结余额
     */
    public void settle(final long deductFromFrozen, final long creditToAvailable) {
        if (deductFromFrozen < 0 || creditToAvailable < 0) {
            throw new IllegalArgumentException(
                "settle amounts must be >= 0, deduct=" + deductFromFrozen
                + " credit=" + creditToAvailable);
        }
        if (frozen < deductFromFrozen) {
            throw new IllegalStateException(
                "settle deduct " + deductFromFrozen + " exceeds frozen " + frozen
                + " for account=" + accountId + " asset=" + assetId);
        }
        frozen    -= deductFromFrozen;
        available += creditToAvailable;
    }

    @Override
    public String toString() {
        return "AccountBalance{accountId=" + accountId
            + ", assetId=" + assetId
            + ", available=" + available
            + ", frozen=" + frozen
            + ", total=" + getTotal()
            + '}';
    }
}
```

---

## 3. MarginAccount（保证金账户）

合约账户按保证金币种维护一个保证金账户，管理钱包余额、未实现盈亏、保证金率等。

文件：`counter-service/src/main/java/com/trading/counter/model/MarginAccount.java`

```java
package com.trading.counter.model;

/**
 * 合约保证金账户（Perp / Futures）。
 *
 * <p>字段说明：
 * <pre>
 *   marginBalance   = walletBalance + unrealizedPnl
 *   availableBalance = marginBalance - initialMargin - maintenanceMargin
 *   marginRatio     = maintenanceMargin / marginBalance  （越小越安全）
 * </pre>
 *
 * <p>强平触发条件：marginRatio >= 1（即维持保证金耗尽全部保证金余额）
 *
 * @author Reln Ding
 */
public final class MarginAccount {

    public final long accountId;

    /** 保证金币种 ID（如 USDT=2） */
    public final int currency;

    /** 钱包余额（已实现盈亏的累积，不含未实现）*/
    private long walletBalance;

    /** 未实现盈亏（随最新成交价实时更新）*/
    private long unrealizedPnl;

    /** 已用初始保证金（所有持仓的初始保证金之和）*/
    private long initialMargin;

    /** 维持保证金（所有持仓的维持保证金之和）*/
    private long maintenanceMargin;

    public MarginAccount(final long accountId, final int currency) {
        this.accountId         = accountId;
        this.currency          = currency;
        this.walletBalance     = 0L;
        this.unrealizedPnl     = 0L;
        this.initialMargin     = 0L;
        this.maintenanceMargin = 0L;
    }

    // ---- 查询 ----

    public long getWalletBalance()      { return walletBalance;     }
    public long getUnrealizedPnl()      { return unrealizedPnl;     }
    public long getInitialMargin()      { return initialMargin;     }
    public long getMaintenanceMargin()  { return maintenanceMargin; }

    /** 保证金余额 = 钱包余额 + 未实现盈亏 */
    public long getMarginBalance() {
        return walletBalance + unrealizedPnl;
    }

    /**
     * 可用余额 = 保证金余额 - 初始保证金。
     * 可用余额是可以再开仓的资金量。
     */
    public long getAvailableBalance() {
        return Math.max(0L, getMarginBalance() - initialMargin);
    }

    /**
     * 保证金率（精度 1/1_000_000）。
     * = maintenanceMargin / marginBalance × 1_000_000
     * 返回 Long.MAX_VALUE 表示 marginBalance <= 0（爆仓）。
     */
    public long getMarginRatioMicros() {
        final long mb = getMarginBalance();
        if (mb <= 0) {
            return Long.MAX_VALUE;
        }
        return maintenanceMargin * 1_000_000L / mb;
    }

    /** 是否触发强平（保证金率 >= 100%）*/
    public boolean isLiquidatable() {
        return getMarginBalance() <= maintenanceMargin;
    }

    // ---- 业务操作 ----

    /** 充值（增加钱包余额）*/
    public void deposit(final long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("deposit amount must be > 0, got: " + amount);
        }
        walletBalance += amount;
    }

    /** 提现（减少钱包余额）*/
    public boolean withdraw(final long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("withdraw amount must be > 0, got: " + amount);
        }
        if (getAvailableBalance() < amount) {
            return false;
        }
        walletBalance -= amount;
        return true;
    }

    /**
     * 开仓冻结保证金。
     *
     * @param im 初始保证金
     * @param mm 维持保证金
     * @return true=成功，false=可用余额不足
     */
    public boolean reserveMargin(final long im, final long mm) {
        if (getAvailableBalance() < im) {
            return false;
        }
        initialMargin     += im;
        maintenanceMargin += mm;
        return true;
    }

    /**
     * 平仓释放保证金。
     *
     * @param im 释放的初始保证金
     * @param mm 释放的维持保证金
     */
    public void releaseMargin(final long im, final long mm) {
        initialMargin     = Math.max(0L, initialMargin - im);
        maintenanceMargin = Math.max(0L, maintenanceMargin - mm);
    }

    /**
     * 更新未实现盈亏（逐笔成交后或定时更新）。
     *
     * @param newUnrealizedPnl 新的未实现盈亏值（可为负）
     */
    public void updateUnrealizedPnl(final long newUnrealizedPnl) {
        this.unrealizedPnl = newUnrealizedPnl;
    }

    /**
     * 平仓结算：将已实现盈亏计入钱包余额，释放对应保证金。
     *
     * @param realizedPnl 已实现盈亏（可为负）
     * @param im          释放的初始保证金
     * @param mm          释放的维持保证金
     */
    public void closeSettle(final long realizedPnl, final long im, final long mm) {
        walletBalance += realizedPnl;
        releaseMargin(im, mm);
    }

    @Override
    public String toString() {
        return "MarginAccount{accountId=" + accountId
            + ", currency=" + currency
            + ", walletBalance=" + walletBalance
            + ", unrealizedPnl=" + unrealizedPnl
            + ", marginBalance=" + getMarginBalance()
            + ", available=" + getAvailableBalance()
            + ", initialMargin=" + initialMargin
            + ", maintenanceMargin=" + maintenanceMargin
            + '}';
    }
}
```

---

## 4. Position（合约仓位）

单个账户在单个交易对上的一个方向仓位（多头或空头）。

文件：`counter-service/src/main/java/com/trading/counter/model/Position.java`

```java
package com.trading.counter.model;

/**
 * 合约仓位（Perp / Futures 单方向）。
 *
 * <p>一个账户在一个交易对上最多同时持有多头仓位和空头仓位（双向持仓模式），
 * 每个方向用独立的 Position 对象表示。
 *
 * <p>均价计算（开仓均价更新规则）：
 * <pre>
 *   新均价 = (旧均价 × 旧数量 + 成交价 × 新增数量) / (旧数量 + 新增数量)
 * </pre>
 *
 * @author Reln Ding
 */
public final class Position {

    public final long accountId;
    public final int  symbolId;

    /** 仓位方向：1=Long（多），2=Short（空）*/
    public final byte side;

    /** 持仓数量（固定精度整数）*/
    private long quantity;

    /** 开仓均价（固定精度整数）*/
    private long entryPrice;

    /** 杠杆倍数 */
    private int leverage;

    /** 保证金模式：1=逐仓（Isolated），2=全仓（Cross）*/
    private byte marginMode;

    /** 该仓位占用的初始保证金（固定精度）*/
    private long initialMargin;

    /** 该仓位的维持保证金（固定精度）*/
    private long maintenanceMargin;

    /** 强平价格（由风控模块计算并缓存）*/
    private long liquidationPrice;

    /** 未实现盈亏（随行情实时更新）*/
    private long unrealizedPnl;

    public static final byte LONG  = 1;
    public static final byte SHORT = 2;

    public Position(final long accountId, final int symbolId, final byte side,
                    final int leverage, final byte marginMode) {
        this.accountId   = accountId;
        this.symbolId    = symbolId;
        this.side        = side;
        this.leverage    = leverage;
        this.marginMode  = marginMode;
        this.quantity    = 0L;
        this.entryPrice  = 0L;
    }

    // ---- 查询 ----

    public long getQuantity()          { return quantity;          }
    public long getEntryPrice()        { return entryPrice;        }
    public int  getLeverage()          { return leverage;          }
    public byte getMarginMode()        { return marginMode;        }
    public long getInitialMargin()     { return initialMargin;     }
    public long getMaintenanceMargin() { return maintenanceMargin; }
    public long getLiquidationPrice()  { return liquidationPrice;  }
    public long getUnrealizedPnl()     { return unrealizedPnl;     }
    public boolean isEmpty()           { return quantity == 0L;    }

    // ---- 业务操作 ----

    /**
     * 开仓（增加仓位，更新均价）。
     *
     * @param tradePrice  成交价
     * @param tradeQty    成交数量
     * @param addIM       增加的初始保证金
     * @param addMM       增加的维持保证金
     */
    public void open(final long tradePrice, final long tradeQty,
                     final long addIM, final long addMM) {
        if (quantity == 0L) {
            entryPrice = tradePrice;
        } else {
            // 加权均价
            entryPrice = (entryPrice * quantity + tradePrice * tradeQty)
                         / (quantity + tradeQty);
        }
        quantity          += tradeQty;
        initialMargin     += addIM;
        maintenanceMargin += addMM;
    }

    /**
     * 平仓（减少仓位，均价不变）。
     *
     * @param tradeQty 平仓数量（必须 <= quantity）
     * @param releaseIM 释放的初始保证金
     * @param releaseMM 释放的维持保证金
     * @return 已实现盈亏（正=盈利，负=亏损）
     * @throws IllegalArgumentException tradeQty > quantity
     */
    public long close(final long tradeQty, final long tradePrice,
                      final long releaseIM, final long releaseMM) {
        if (tradeQty > quantity) {
            throw new IllegalArgumentException(
                "close qty " + tradeQty + " > position qty " + quantity);
        }
        // 已实现盈亏 = (成交价 - 均价) × 数量 × 方向系数
        final long priceDiff = (side == LONG)
            ? tradePrice - entryPrice
            : entryPrice - tradePrice;
        final long realizedPnl = priceDiff * tradeQty;   // 精度处理由上层负责

        quantity          -= tradeQty;
        initialMargin     -= releaseIM;
        maintenanceMargin -= releaseMM;

        if (quantity == 0L) {
            entryPrice        = 0L;
            initialMargin     = 0L;
            maintenanceMargin = 0L;
            unrealizedPnl     = 0L;
            liquidationPrice  = 0L;
        }
        return realizedPnl;
    }

    /**
     * 更新未实现盈亏（按最新成交价或标记价格）。
     *
     * @param markPrice 标记价格（或最新成交价）
     */
    public void updateUnrealizedPnl(final long markPrice) {
        if (quantity == 0L) {
            unrealizedPnl = 0L;
            return;
        }
        final long priceDiff = (side == LONG)
            ? markPrice - entryPrice
            : entryPrice - markPrice;
        unrealizedPnl = priceDiff * quantity;
    }

    /**
     * 更新强平价格（由 PositionManager 在开/平仓后调用）。
     */
    public void setLiquidationPrice(final long liqPrice) {
        this.liquidationPrice = liqPrice;
    }

    @Override
    public String toString() {
        return "Position{accountId=" + accountId
            + ", symbolId=" + symbolId
            + ", side=" + side
            + ", qty=" + quantity
            + ", entryPrice=" + entryPrice
            + ", leverage=" + leverage
            + ", unrealizedPnl=" + unrealizedPnl
            + ", liqPrice=" + liquidationPrice
            + '}';
    }
}
```

---

## 5. OrderState（订单状态）

柜台对每笔活跃订单维护运行时状态，用于成交回报生成和撤单校验。

文件：`counter-service/src/main/java/com/trading/counter/model/OrderState.java`

```java
package com.trading.counter.model;

/**
 * 柜台侧订单运行时状态。
 *
 * <p>只存储撮合引擎回报处理和回报生成所需的字段，
 * 不重复存储订单簿中已有的 price/quantity（撮合引擎是权威来源）。
 *
 * @author Reln Ding
 */
public final class OrderState {

    // ---- 状态常量 ----
    public static final byte STATUS_PENDING         = 5;
    public static final byte STATUS_NEW             = 0;
    public static final byte STATUS_PARTIALLY_FILLED = 1;
    public static final byte STATUS_FILLED          = 2;
    public static final byte STATUS_CANCELLED       = 3;
    public static final byte STATUS_REJECTED        = 4;

    public final long orderId;
    public final long correlationId;
    public final long accountId;
    public final int  symbolId;
    public final byte side;
    public final byte orderType;
    public final long price;
    public final long quantity;

    /** 当前累计成交量 */
    private long filledQty;

    /** 订单当前状态 */
    private byte status;

    /**
     * 冻结的资金量（用于撤单时解冻）。
     * 现货买单：冻结 quote 资产；现货卖单：冻结 base 资产。
     * 合约：冻结保证金。
     */
    private long frozenAmount;

    /** 冻结的资产 ID */
    private int frozenAssetId;

    public OrderState(final long orderId, final long correlationId,
                      final long accountId, final int symbolId,
                      final byte side, final byte orderType,
                      final long price, final long quantity) {
        this.orderId       = orderId;
        this.correlationId = correlationId;
        this.accountId     = accountId;
        this.symbolId      = symbolId;
        this.side          = side;
        this.orderType     = orderType;
        this.price         = price;
        this.quantity      = quantity;
        this.filledQty     = 0L;
        this.status        = STATUS_PENDING;
    }

    // ---- 查询 ----

    public long getFilledQty()    { return filledQty;           }
    public long getLeavesQty()    { return quantity - filledQty; }
    public byte getStatus()       { return status;              }
    public long getFrozenAmount() { return frozenAmount;        }
    public int  getFrozenAssetId(){ return frozenAssetId;       }

    public boolean isActive() {
        return status == STATUS_NEW || status == STATUS_PARTIALLY_FILLED
               || status == STATUS_PENDING;
    }

    public boolean isTerminal() {
        return status == STATUS_FILLED || status == STATUS_CANCELLED
               || status == STATUS_REJECTED;
    }

    // ---- 状态变更 ----

    public void confirmNew() {
        this.status = STATUS_NEW;
    }

    public void reject() {
        this.status = STATUS_REJECTED;
    }

    public void cancel() {
        this.status = STATUS_CANCELLED;
    }

    /**
     * 部分或全量成交更新。
     *
     * @param fillQty 本次成交量
     */
    public void fill(final long fillQty) {
        this.filledQty += fillQty;
        this.status = (this.filledQty >= this.quantity)
            ? STATUS_FILLED
            : STATUS_PARTIALLY_FILLED;
    }

    /**
     * 设置冻结资金信息（挂单时由 FreezeHandler 调用）。
     */
    public void setFrozen(final long frozenAmount, final int frozenAssetId) {
        this.frozenAmount  = frozenAmount;
        this.frozenAssetId = frozenAssetId;
    }

    @Override
    public String toString() {
        return "OrderState{orderId=" + orderId
            + ", accountId=" + accountId
            + ", symbolId=" + symbolId
            + ", status=" + status
            + ", filledQty=" + filledQty
            + ", leavesQty=" + getLeavesQty()
            + '}';
    }
}
```

---

## 6. SymbolConfig（交易对配置）

交易对的静态配置，由管理员写入后通过 Raft 日志复制到所有节点。

文件：`counter-service/src/main/java/com/trading/counter/model/SymbolConfig.java`

```java
package com.trading.counter.model;

/**
 * 交易对配置（只读，运行期不变）。
 *
 * <p>所有精度相关字段以"小数位数"表示，如 pricePrecision=2 表示最小价格单位 0.01。
 *
 * @author Reln Ding
 */
public final class SymbolConfig {

    // ---- 交易对类型 ----
    public static final byte TYPE_SPOT    = 1;
    public static final byte TYPE_PERP    = 2;
    public static final byte TYPE_FUTURES = 3;

    // ---- 状态 ----
    public static final byte STATUS_PRE_TRADING = 0;
    public static final byte STATUS_TRADING     = 1;
    public static final byte STATUS_SUSPENDED   = 2;
    public static final byte STATUS_DELISTED    = 3;

    public final int  symbolId;
    public final int  baseCurrency;        // 基础货币 ID（如 BTC=1）
    public final int  quoteCurrency;       // 计价货币 ID（如 USDT=2）
    public final byte symbolType;          // 1=Spot, 2=Perp, 3=Futures
    public byte       status;              // 可热更新

    public final int  pricePrecision;      // 价格小数位
    public final int  quantityPrecision;   // 数量小数位
    public final long priceTick;           // 最小价格变动（固定精度 long）
    public final long quantityStep;        // 最小数量步长
    public final long minOrderQty;         // 最小下单量
    public final long maxOrderQty;         // 最大下单量
    public final int  maxLeverage;         // 最大杠杆（Spot=1）

    public final int  makerFeeRateMicros;  // Maker 费率（1/1_000_000）
    public final int  takerFeeRateMicros;  // Taker 费率

    // ---- 合约专属字段（Spot 留 0）----
    public final long contractSize;        // 合约面值
    public final int  settleCurrency;      // 结算货币 ID
    public final long deliveryTimeNs;      // 交割时间（0=永续）

    /** 价格笼子比例（百万分之一，如 100_000 = 10%）*/
    public final int  priceBandRatioMicros;

    public SymbolConfig(final int symbolId,
                        final int baseCurrency, final int quoteCurrency,
                        final byte symbolType,
                        final int pricePrecision, final int quantityPrecision,
                        final long priceTick, final long quantityStep,
                        final long minOrderQty, final long maxOrderQty,
                        final int maxLeverage,
                        final int makerFeeRateMicros, final int takerFeeRateMicros,
                        final long contractSize, final int settleCurrency,
                        final long deliveryTimeNs,
                        final int priceBandRatioMicros) {
        this.symbolId             = symbolId;
        this.baseCurrency         = baseCurrency;
        this.quoteCurrency        = quoteCurrency;
        this.symbolType           = symbolType;
        this.status               = STATUS_TRADING;
        this.pricePrecision       = pricePrecision;
        this.quantityPrecision    = quantityPrecision;
        this.priceTick            = priceTick;
        this.quantityStep         = quantityStep;
        this.minOrderQty          = minOrderQty;
        this.maxOrderQty          = maxOrderQty;
        this.maxLeverage          = maxLeverage;
        this.makerFeeRateMicros   = makerFeeRateMicros;
        this.takerFeeRateMicros   = takerFeeRateMicros;
        this.contractSize         = contractSize;
        this.settleCurrency       = settleCurrency;
        this.deliveryTimeNs       = deliveryTimeNs;
        this.priceBandRatioMicros = priceBandRatioMicros;
    }

    public boolean isSpot()    { return symbolType == TYPE_SPOT;    }
    public boolean isPerp()    { return symbolType == TYPE_PERP;    }
    public boolean isFutures() { return symbolType == TYPE_FUTURES; }
    public boolean isTrading() { return status == STATUS_TRADING;   }

    /** 构造一个典型的现货交易对（BTC/USDT）用于测试 */
    public static SymbolConfig btcUsdt() {
        return new SymbolConfig(
            1, 1, 2, TYPE_SPOT,
            2, 6,           // price precision=0.01, qty precision=0.000001
            1L, 1L,         // priceTick=0.01, qtyStep=0.000001
            1L, 1_000_000_000L,  // min 0.000001 BTC, max 1000 BTC
            1,              // Spot leverage=1
            1_000, 2_000,   // maker 0.1%, taker 0.2%
            0L, 0, 0L,      // 合约字段为 0
            100_000         // 价格笼子 10%
        );
    }

    /** 构造一个典型的永续合约（BTC-PERP）用于测试 */
    public static SymbolConfig btcPerpUsdt() {
        return new SymbolConfig(
            2, 1, 2, TYPE_PERP,
            2, 0,
            1L, 1L,
            1L, 1_000_000L,
            125,
            1_000, 2_000,
            100_000L,   // contractSize = 0.01 BTC
            2, 0L,
            50_000      // 价格笼子 5%
        );
    }
}
```

---

## 7. 领域模型单元测试

文件：`counter-service/src/test/java/com/trading/counter/model/DomainModelTest.java`

```java
package com.trading.counter.model;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 领域模型单元测试。
 * 覆盖 AccountBalance / MarginAccount / Position / OrderState 所有分支。
 *
 * @author Reln Ding
 */
class DomainModelTest {

    // ================================================================
    // AccountBalance
    // ================================================================

    @Nested
    @DisplayName("AccountBalance")
    class AccountBalanceTests {

        private AccountBalance bal;

        @BeforeEach
        void setUp() {
            bal = new AccountBalance(1L, 2, 1_000_000L, 0L);
        }

        @Test @DisplayName("deposit 增加 available")
        void deposit() {
            bal.deposit(500_000L);
            assertEquals(1_500_000L, bal.getAvailable());
            assertEquals(1_500_000L, bal.getTotal());
        }

        @Test @DisplayName("deposit 非正数抛异常")
        void depositNonPositiveThrows() {
            assertThrows(IllegalArgumentException.class, () -> bal.deposit(0L));
            assertThrows(IllegalArgumentException.class, () -> bal.deposit(-1L));
        }

        @Test @DisplayName("withdraw 成功减少 available")
        void withdrawSuccess() {
            assertTrue(bal.withdraw(400_000L));
            assertEquals(600_000L, bal.getAvailable());
        }

        @Test @DisplayName("withdraw 余额不足返回 false")
        void withdrawInsufficient() {
            assertFalse(bal.withdraw(2_000_000L));
            assertEquals(1_000_000L, bal.getAvailable());  // 未变化
        }

        @Test @DisplayName("withdraw 非正数抛异常")
        void withdrawNonPositiveThrows() {
            assertThrows(IllegalArgumentException.class, () -> bal.withdraw(0L));
        }

        @Test @DisplayName("freeze 成功：available→frozen")
        void freezeSuccess() {
            assertTrue(bal.freeze(300_000L));
            assertEquals(700_000L, bal.getAvailable());
            assertEquals(300_000L, bal.getFrozen());
            assertEquals(1_000_000L, bal.getTotal());
        }

        @Test @DisplayName("freeze 不足返回 false，状态不变")
        void freezeInsufficient() {
            assertFalse(bal.freeze(2_000_000L));
            assertEquals(1_000_000L, bal.getAvailable());
            assertEquals(0L, bal.getFrozen());
        }

        @Test @DisplayName("freeze 非正数抛异常")
        void freezeNonPositiveThrows() {
            assertThrows(IllegalArgumentException.class, () -> bal.freeze(-1L));
        }

        @Test @DisplayName("unfreeze 成功：frozen→available")
        void unfreezeSuccess() {
            bal.freeze(300_000L);
            bal.unfreeze(100_000L);
            assertEquals(800_000L, bal.getAvailable());
            assertEquals(200_000L, bal.getFrozen());
        }

        @Test @DisplayName("unfreeze 超出冻结量抛 IllegalStateException")
        void unfreezeExceedsThrows() {
            bal.freeze(100_000L);
            assertThrows(IllegalStateException.class, () -> bal.unfreeze(200_000L));
        }

        @Test @DisplayName("unfreeze 非正数抛异常")
        void unfreezeNonPositiveThrows() {
            assertThrows(IllegalArgumentException.class, () -> bal.unfreeze(0L));
        }

        @Test @DisplayName("settle 正确划转：frozen→available")
        void settleSuccess() {
            bal.freeze(300_000L);
            // 买方：冻结 300 USDT，收到 6 BTC (以 base 资产的 available 增加模拟)
            bal.settle(300_000L, 0L);  // quote 扣除 300，base 在另一个 AccountBalance 对象
            assertEquals(0L, bal.getFrozen());
            assertEquals(700_000L, bal.getAvailable());
        }

        @Test @DisplayName("settle 负数参数抛异常")
        void settleNegativeThrows() {
            assertThrows(IllegalArgumentException.class, () -> bal.settle(-1L, 0L));
            assertThrows(IllegalArgumentException.class, () -> bal.settle(0L, -1L));
        }

        @Test @DisplayName("settle deduct 超过 frozen 抛 IllegalStateException")
        void settleDeductExceedsThrows() {
            bal.freeze(100_000L);
            assertThrows(IllegalStateException.class, () -> bal.settle(200_000L, 0L));
        }
    }

    // ================================================================
    // MarginAccount
    // ================================================================

    @Nested
    @DisplayName("MarginAccount")
    class MarginAccountTests {

        private MarginAccount margin;

        @BeforeEach
        void setUp() {
            margin = new MarginAccount(1L, 2);
            margin.deposit(10_000_00L);  // 10000 USDT
        }

        @Test @DisplayName("deposit 增加 walletBalance 和 marginBalance")
        void deposit() {
            margin.deposit(1_000_00L);
            assertEquals(11_000_00L, margin.getWalletBalance());
            assertEquals(11_000_00L, margin.getMarginBalance());
        }

        @Test @DisplayName("deposit 非正数抛异常")
        void depositNonPositiveThrows() {
            assertThrows(IllegalArgumentException.class, () -> margin.deposit(0L));
        }

        @Test @DisplayName("reserveMargin 成功冻结保证金")
        void reserveMarginSuccess() {
            assertTrue(margin.reserveMargin(1_000_00L, 500_00L));
            assertEquals(1_000_00L, margin.getInitialMargin());
            assertEquals(500_00L, margin.getMaintenanceMargin());
            assertEquals(9_000_00L, margin.getAvailableBalance());
        }

        @Test @DisplayName("reserveMargin 可用不足返回 false")
        void reserveMarginInsufficient() {
            assertFalse(margin.reserveMargin(20_000_00L, 10_000_00L));
            assertEquals(0L, margin.getInitialMargin());
        }

        @Test @DisplayName("releaseMargin 释放正确")
        void releaseMargin() {
            margin.reserveMargin(2_000_00L, 1_000_00L);
            margin.releaseMargin(1_000_00L, 500_00L);
            assertEquals(1_000_00L, margin.getInitialMargin());
            assertEquals(500_00L, margin.getMaintenanceMargin());
        }

        @Test @DisplayName("releaseMargin 不会导致负值")
        void releaseMarginNoNegative() {
            margin.releaseMargin(999_999_00L, 999_999_00L);
            assertEquals(0L, margin.getInitialMargin());
            assertEquals(0L, margin.getMaintenanceMargin());
        }

        @Test @DisplayName("isLiquidatable 当 marginBalance <= maintenanceMargin 时为 true")
        void isLiquidatable() {
            // marginBalance = walletBalance + unrealizedPnl
            margin.updateUnrealizedPnl(-9_500_00L);  // 巨亏，marginBalance = 500
            margin.reserveMargin(500_00L, 600_00L);   // mm=600 > marginBalance=500
            assertTrue(margin.isLiquidatable());
        }

        @Test @DisplayName("getMarginRatioMicros 返回 MAX_VALUE 当 marginBalance <= 0")
        void marginRatioWhenBalanceNonPositive() {
            margin.updateUnrealizedPnl(-10_000_00L);  // marginBalance = 0
            assertEquals(Long.MAX_VALUE, margin.getMarginRatioMicros());
        }

        @Test @DisplayName("closeSettle 增加 walletBalance 并释放保证金")
        void closeSettle() {
            margin.reserveMargin(2_000_00L, 1_000_00L);
            margin.closeSettle(500_00L, 2_000_00L, 1_000_00L);
            assertEquals(10_500_00L, margin.getWalletBalance());
            assertEquals(0L, margin.getInitialMargin());
        }

        @Test @DisplayName("withdraw 成功减少 walletBalance")
        void withdrawSuccess() {
            assertTrue(margin.withdraw(5_000_00L));
            assertEquals(5_000_00L, margin.getWalletBalance());
        }

        @Test @DisplayName("withdraw 不足（超出 available）返回 false")
        void withdrawInsufficient() {
            margin.reserveMargin(8_000_00L, 4_000_00L);
            assertFalse(margin.withdraw(5_000_00L));
        }

        @Test @DisplayName("withdraw 非正数抛异常")
        void withdrawNonPositiveThrows() {
            assertThrows(IllegalArgumentException.class, () -> margin.withdraw(0L));
        }
    }

    // ================================================================
    // Position
    // ================================================================

    @Nested
    @DisplayName("Position")
    class PositionTests {

        private Position longPos;

        @BeforeEach
        void setUp() {
            longPos = new Position(1L, 1, Position.LONG, 10, (byte) 1);
        }

        @Test @DisplayName("初始状态为空仓")
        void initiallyEmpty() {
            assertTrue(longPos.isEmpty());
            assertEquals(0L, longPos.getQuantity());
        }

        @Test @DisplayName("open 首次开仓，均价 = 成交价")
        void openFirstTime() {
            longPos.open(5000_00L, 100L, 500_00L, 250_00L);
            assertEquals(5000_00L, longPos.getEntryPrice());
            assertEquals(100L, longPos.getQuantity());
            assertFalse(longPos.isEmpty());
        }

        @Test @DisplayName("open 加仓更新均价")
        void openAddPosition() {
            longPos.open(5000_00L, 100L, 500_00L, 250_00L);
            longPos.open(6000_00L, 100L, 600_00L, 300_00L);
            // 均价 = (5000*100 + 6000*100) / 200 = 5500
            assertEquals(5500_00L, longPos.getEntryPrice());
            assertEquals(200L, longPos.getQuantity());
        }

        @Test @DisplayName("close 多头盈利，realizedPnl > 0")
        void closeLongProfit() {
            longPos.open(5000_00L, 100L, 500_00L, 250_00L);
            final long pnl = longPos.close(50L, 6000_00L, 250_00L, 125_00L);
            // pnl = (6000-5000) * 50 = 50000
            assertEquals(1000_00L * 50L, pnl);
            assertEquals(50L, longPos.getQuantity());
        }

        @Test @DisplayName("close 空头亏损，realizedPnl < 0")
        void closeShortLoss() {
            final Position shortPos = new Position(1L, 1, Position.SHORT, 10, (byte)1);
            shortPos.open(5000_00L, 100L, 500_00L, 250_00L);
            // 空头以 6000 平仓，亏损
            final long pnl = shortPos.close(100L, 6000_00L, 500_00L, 250_00L);
            // pnl = (5000-6000) * 100 = -100000
            assertEquals(-1000_00L * 100L, pnl);
            assertTrue(shortPos.isEmpty());
        }

        @Test @DisplayName("close 数量超出持仓抛异常")
        void closeExceedsQuantityThrows() {
            longPos.open(5000_00L, 50L, 250_00L, 125_00L);
            assertThrows(IllegalArgumentException.class,
                         () -> longPos.close(100L, 6000_00L, 0L, 0L));
        }

        @Test @DisplayName("全平后仓位归零")
        void fullCloseResetsFields() {
            longPos.open(5000_00L, 100L, 500_00L, 250_00L);
            longPos.close(100L, 5000_00L, 500_00L, 250_00L);
            assertTrue(longPos.isEmpty());
            assertEquals(0L, longPos.getEntryPrice());
            assertEquals(0L, longPos.getUnrealizedPnl());
        }

        @Test @DisplayName("updateUnrealizedPnl 多头盈利时为正")
        void updateUnrealizedPnlLongProfit() {
            longPos.open(5000_00L, 100L, 500_00L, 250_00L);
            longPos.updateUnrealizedPnl(5500_00L);
            assertEquals(500_00L * 100L, longPos.getUnrealizedPnl());
        }

        @Test @DisplayName("updateUnrealizedPnl 空仓时归零")
        void updateUnrealizedPnlEmpty() {
            longPos.updateUnrealizedPnl(9999_00L);
            assertEquals(0L, longPos.getUnrealizedPnl());
        }
    }

    // ================================================================
    // OrderState
    // ================================================================

    @Nested
    @DisplayName("OrderState")
    class OrderStateTests {

        private OrderState order;

        @BeforeEach
        void setUp() {
            order = new OrderState(1L, 100L, 1001L, 1,
                                   (byte)1, (byte)1, 5000_00L, 100L);
        }

        @Test @DisplayName("初始状态为 PENDING，isActive=true")
        void initialState() {
            assertEquals(OrderState.STATUS_PENDING, order.getStatus());
            assertTrue(order.isActive());
            assertFalse(order.isTerminal());
        }

        @Test @DisplayName("confirmNew 后状态为 NEW")
        void confirmNew() {
            order.confirmNew();
            assertEquals(OrderState.STATUS_NEW, order.getStatus());
        }

        @Test @DisplayName("reject 后状态为 REJECTED，isTerminal=true")
        void reject() {
            order.reject();
            assertEquals(OrderState.STATUS_REJECTED, order.getStatus());
            assertTrue(order.isTerminal());
            assertFalse(order.isActive());
        }

        @Test @DisplayName("cancel 后状态为 CANCELLED，isTerminal=true")
        void cancel() {
            order.confirmNew();
            order.cancel();
            assertEquals(OrderState.STATUS_CANCELLED, order.getStatus());
            assertTrue(order.isTerminal());
        }

        @Test @DisplayName("fill 部分成交后状态为 PARTIALLY_FILLED")
        void partialFill() {
            order.confirmNew();
            order.fill(40L);
            assertEquals(OrderState.STATUS_PARTIALLY_FILLED, order.getStatus());
            assertEquals(40L, order.getFilledQty());
            assertEquals(60L, order.getLeavesQty());
            assertTrue(order.isActive());
        }

        @Test @DisplayName("fill 全量成交后状态为 FILLED，isTerminal=true")
        void fullFill() {
            order.confirmNew();
            order.fill(100L);
            assertEquals(OrderState.STATUS_FILLED, order.getStatus());
            assertEquals(100L, order.getFilledQty());
            assertEquals(0L, order.getLeavesQty());
            assertTrue(order.isTerminal());
        }

        @Test @DisplayName("setFrozen 正确保存冻结信息")
        void setFrozen() {
            order.setFrozen(500_000L, 2);
            assertEquals(500_000L, order.getFrozenAmount());
            assertEquals(2, order.getFrozenAssetId());
        }
    }
}
```

### 7.1 运行单元测试

```bash
cd trading-platform
mvn test -pl counter-service -Dtest=DomainModelTest -Dcheckstyle.skip=true
# 期望：Tests run: 38, Failures: 0, Errors: 0
```

---

## Part 1 完成检查清单

- [ ] `AccountBalance`：freeze/unfreeze/settle 三操作的正向、边界、异常路径全覆盖
- [ ] `MarginAccount`：reserveMargin 余额不足返回 false；isLiquidatable 触发条件正确
- [ ] `Position`：open 均价计算正确；close 多空方向 PnL 符号正确；全平后字段归零
- [ ] `OrderState`：PENDING → NEW → PARTIALLY_FILLED → FILLED 状态流转正确；isTerminal 覆盖所有终态
- [ ] `SymbolConfig`：工厂方法 `btcUsdt()` / `btcPerpUsdt()` 字段正确
- [ ] `DomainModelTest` 38 个测试全部通过

---

## 下一步：Part 2

Part 1 完成后，进入 **Part 2：AccountManager + PositionManager**，包括：

1. `AccountManager`：多资产余额管理、Spot 下单冻结/解冻、成交结算
2. `PositionManager`：多交易对仓位管理、开平仓均价维护、强平价格计算
3. `SymbolConfigManager`：交易对配置注册与查询
4. 对应单元测试（含 Spot 全链路余额变化验证）
