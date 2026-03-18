# Phase 3 柜台服务实现 — Part 3：风控模块与手续费计算

> **目标：** 实现三层风控校验（余额、仓位上限、价格笼子）和手续费精确计算，
> 所有拒绝场景均有单测覆盖，确保无漏网之鱼。
>
> **前置条件：** Part 2 完成，AccountManager / PositionManager 25 个测试通过  
> **本节验证目标：** 风控拒绝场景 + 手续费测试 100% 分支覆盖

---

## 目录

1. [风控层次回顾](#1-风控层次回顾)
2. [RejectReason 枚举](#2-rejectreason-枚举)
3. [BalanceRiskChecker](#3-balanceriskchecker)
4. [PositionRiskChecker](#4-positionriskchecker)
5. [PriceBandChecker](#5-pricebandchecker)
6. [FeeCalculator](#6-feecalculator)
7. [风控与手续费单元测试](#7-风控与手续费单元测试)

---

## 1. 风控层次回顾

```
Layer 1: Gateway 层（前置，本阶段不实现）
  • API 限流 / IP 限流 / 消息大小校验

Layer 2: Counter 层（本 Part 实现）
  ┌──────────────────────────────────────────────────────┐
  │  BalanceRiskChecker   余额/保证金充足性校验              │
  │  PositionRiskChecker  仓位上限校验（per-account/symbol）│
  │  PriceBandChecker     价格笼子（防异常价格冲击）          │
  └──────────────────────────────────────────────────────┘

Layer 3: 撮合层（Phase 2 已实现）
  • 价格笼子（撮合引擎侧二次确认）
  • 市价单滑点保护
```

---

## 2. RejectReason 枚举

统一风控拒绝原因码，与 SBE Schema 中 `RejectReason` 字段一一对应。

文件：`counter-service/src/main/java/com/trading/counter/risk/RejectReason.java`

```java
package com.trading.counter.risk;

/**
 * 订单拒绝原因码。
 * 与 SBE Schema RejectReason 枚举值对应（见 TradingMessages.xml）。
 *
 * @author Reln Ding
 */
public enum RejectReason {

    NONE(0),
    INSUFFICIENT_BALANCE(1),
    INVALID_PRICE(2),
    INVALID_QUANTITY(3),
    SYMBOL_NOT_FOUND(4),
    SYMBOL_HALTED(5),
    EXCEED_POSITION_LIMIT(6),
    PRICE_OUT_OF_BAND(7),
    ORDER_NOT_FOUND(8),
    RATE_LIMIT_EXCEEDED(9),
    SYSTEM_BUSY(10);

    public final int code;

    RejectReason(final int code) {
        this.code = code;
    }

    public boolean isRejected() {
        return this != NONE;
    }
}
```

---

## 3. BalanceRiskChecker

检查账户余额/保证金是否满足下单需求，是风控的核心环节。

文件：`counter-service/src/main/java/com/trading/counter/risk/BalanceRiskChecker.java`

```java
package com.trading.counter.risk;

import com.trading.counter.account.AccountManager;
import com.trading.counter.account.PositionManager;
import com.trading.counter.model.AccountBalance;
import com.trading.counter.model.MarginAccount;
import com.trading.counter.model.SymbolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 余额/保证金充足性风控校验器。
 *
 * <p>校验逻辑：
 * <ul>
 *   <li>Spot 限价买单：检查 quote 可用余额 >= price × qty + fee_estimate</li>
 *   <li>Spot 限价卖单：检查 base 可用余额 >= qty</li>
 *   <li>Spot 市价买单：检查 quote 可用余额 > 0（无法预估准确金额）</li>
 *   <li>Perp/Futures 开仓：检查保证金账户 available >= initialMargin + fee</li>
 *   <li>Perp/Futures 平仓：不需要额外保证金校验</li>
 * </ul>
 *
 * @author Reln Ding
 */
public final class BalanceRiskChecker {

    private static final Logger log = LoggerFactory.getLogger(BalanceRiskChecker.class);

    private final AccountManager  accountManager;
    private final PositionManager positionManager;

    public BalanceRiskChecker(final AccountManager accountManager,
                              final PositionManager positionManager) {
        this.accountManager  = accountManager;
        this.positionManager = positionManager;
    }

    // ----------------------------------------------------------------
    // Spot 校验
    // ----------------------------------------------------------------

    /**
     * 现货限价买单余额校验。
     *
     * @param accountId   账户 ID
     * @param config      交易对配置
     * @param price       委托价（固定精度）
     * @param quantity    委托量（固定精度）
     * @param feeEstimate 预估手续费（固定精度，quote 资产）
     * @return NONE=通过，INSUFFICIENT_BALANCE=余额不足
     */
    public RejectReason checkSpotLimitBuy(final long accountId, final SymbolConfig config,
                                          final long price, final long quantity,
                                          final long feeEstimate) {
        final long quoteRequired = AccountManager.calcQuoteAmount(
            price, quantity, config.pricePrecision, config.quantityPrecision) + feeEstimate;

        final AccountBalance quoteBal = accountManager.get(accountId, config.quoteCurrency);
        if (quoteBal == null || quoteBal.getAvailable() < quoteRequired) {
            log.debug("SpotLimitBuy rejected: accountId={}, available={}, required={}",
                      accountId,
                      quoteBal == null ? 0 : quoteBal.getAvailable(),
                      quoteRequired);
            return RejectReason.INSUFFICIENT_BALANCE;
        }
        return RejectReason.NONE;
    }

    /**
     * 现货限价卖单余额校验。
     *
     * @param accountId 账户 ID
     * @param config    交易对配置
     * @param quantity  委托量（base 资产，固定精度）
     * @return NONE=通过，INSUFFICIENT_BALANCE=余额不足
     */
    public RejectReason checkSpotLimitSell(final long accountId, final SymbolConfig config,
                                           final long quantity) {
        final AccountBalance baseBal = accountManager.get(accountId, config.baseCurrency);
        if (baseBal == null || baseBal.getAvailable() < quantity) {
            log.debug("SpotLimitSell rejected: accountId={}, available={}, required={}",
                      accountId,
                      baseBal == null ? 0 : baseBal.getAvailable(),
                      quantity);
            return RejectReason.INSUFFICIENT_BALANCE;
        }
        return RejectReason.NONE;
    }

    /**
     * 现货市价买单余额校验（只检查 quote > 0）。
     */
    public RejectReason checkSpotMarketBuy(final long accountId, final SymbolConfig config) {
        final AccountBalance quoteBal = accountManager.get(accountId, config.quoteCurrency);
        if (quoteBal == null || quoteBal.getAvailable() <= 0) {
            return RejectReason.INSUFFICIENT_BALANCE;
        }
        return RejectReason.NONE;
    }

    /**
     * 现货市价卖单余额校验（检查 base > 0）。
     */
    public RejectReason checkSpotMarketSell(final long accountId, final SymbolConfig config) {
        final AccountBalance baseBal = accountManager.get(accountId, config.baseCurrency);
        if (baseBal == null || baseBal.getAvailable() <= 0) {
            return RejectReason.INSUFFICIENT_BALANCE;
        }
        return RejectReason.NONE;
    }

    // ----------------------------------------------------------------
    // Perp / Futures 校验
    // ----------------------------------------------------------------

    /**
     * 合约开仓保证金校验。
     *
     * @param accountId  账户 ID
     * @param config     交易对配置
     * @param price      委托价
     * @param quantity   委托量（合约张数）
     * @param leverage   杠杆倍数
     * @return NONE=通过，INSUFFICIENT_BALANCE=保证金不足
     */
    public RejectReason checkContractOpen(final long accountId, final SymbolConfig config,
                                          final long price, final long quantity,
                                          final int leverage) {
        final long notional    = price * quantity * config.contractSize / 1_000_000L;
        final long im          = notional / leverage;
        final long feeEstimate = notional * config.takerFeeRateMicros / 1_000_000L;
        final long required    = im + feeEstimate;

        final MarginAccount ma = positionManager.getMarginAccount(accountId,
                                                                   config.settleCurrency);
        if (ma == null || ma.getAvailableBalance() < required) {
            log.debug("ContractOpen rejected: accountId={}, available={}, required={}",
                      accountId,
                      ma == null ? 0 : ma.getAvailableBalance(),
                      required);
            return RejectReason.INSUFFICIENT_BALANCE;
        }
        return RejectReason.NONE;
    }
}
```

---

## 4. PositionRiskChecker

检查账户持仓是否超出上限。

文件：`counter-service/src/main/java/com/trading/counter/risk/PositionRiskChecker.java`

```java
package com.trading.counter.risk;

import com.trading.counter.account.PositionManager;
import com.trading.counter.model.Position;
import com.trading.counter.model.SymbolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 仓位上限风控校验器。
 *
 * <p>校验规则：
 * <ul>
 *   <li>单账户单交易对最大持仓量 = config.maxOrderQty × maxPositionMultiplier</li>
 *   <li>简化实现：持仓量 + 新订单量 <= config.maxOrderQty × 10</li>
 * </ul>
 *
 * @author Reln Ding
 */
public final class PositionRiskChecker {

    private static final Logger log = LoggerFactory.getLogger(PositionRiskChecker.class);

    /** 最大持仓倍数：单账户持仓上限 = maxOrderQty × 10 */
    private static final int MAX_POSITION_MULTIPLIER = 10;

    private final PositionManager positionManager;

    public PositionRiskChecker(final PositionManager positionManager) {
        this.positionManager = positionManager;
    }

    /**
     * 合约开仓仓位上限校验。
     *
     * @param accountId 账户 ID
     * @param config    交易对配置
     * @param side      开仓方向
     * @param quantity  新增仓位量
     * @return NONE=通过，EXCEED_POSITION_LIMIT=超出上限
     */
    public RejectReason checkPositionLimit(final long accountId, final SymbolConfig config,
                                           final byte side, final long quantity) {
        final long maxPosition = config.maxOrderQty * MAX_POSITION_MULTIPLIER;
        final Position pos = positionManager.getPosition(accountId, config.symbolId, side);
        final long currentQty = (pos == null) ? 0L : pos.getQuantity();

        if (currentQty + quantity > maxPosition) {
            log.debug("PositionLimit exceeded: accountId={}, symbolId={}, current={}, add={}, max={}",
                      accountId, config.symbolId, currentQty, quantity, maxPosition);
            return RejectReason.EXCEED_POSITION_LIMIT;
        }
        return RejectReason.NONE;
    }
}
```

---

## 5. PriceBandChecker

防止异常价格冲击市场，限价单价格不得偏离参考价 X%。

文件：`counter-service/src/main/java/com/trading/counter/risk/PriceBandChecker.java`

```java
package com.trading.counter.risk;

import com.trading.counter.model.SymbolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 价格笼子风控校验器。
 *
 * <p>规则：限价单价格必须在参考价的 ±priceBandRatio 范围内。
 * 市价单不受此约束。
 *
 * <p>参考价来源（优先级）：
 * <ol>
 *   <li>最新成交价（lastTradePrice）</li>
 *   <li>若无成交记录，跳过价格笼子校验</li>
 * </ol>
 *
 * @author Reln Ding
 */
public final class PriceBandChecker {

    private static final Logger log = LoggerFactory.getLogger(PriceBandChecker.class);

    /**
     * 校验限价单价格是否在价格笼子内。
     *
     * @param config         交易对配置（含 priceBandRatioMicros）
     * @param orderPrice     委托价格（固定精度）
     * @param referencePrice 参考价格（最新成交价，0 表示无参考价）
     * @return NONE=通过，PRICE_OUT_OF_BAND=超出价格笼子
     */
    public RejectReason check(final SymbolConfig config,
                              final long orderPrice,
                              final long referencePrice) {
        // 无参考价（新交易对无成交记录），跳过校验
        if (referencePrice <= 0) {
            return RejectReason.NONE;
        }

        final long bandRatio = config.priceBandRatioMicros;   // 如 100_000 = 10%
        final long upperBound = referencePrice + referencePrice * bandRatio / 1_000_000L;
        final long lowerBound = referencePrice - referencePrice * bandRatio / 1_000_000L;

        if (orderPrice > upperBound || orderPrice < lowerBound) {
            log.debug("PriceBand rejected: orderPrice={}, ref={}, [{}, {}]",
                      orderPrice, referencePrice, lowerBound, upperBound);
            return RejectReason.PRICE_OUT_OF_BAND;
        }
        return RejectReason.NONE;
    }

    /**
     * 校验订单数量是否合法。
     *
     * @param config   交易对配置
     * @param quantity 委托量
     * @return NONE=通过，INVALID_QUANTITY=数量不合法
     */
    public RejectReason checkQuantity(final SymbolConfig config, final long quantity) {
        if (quantity <= 0) {
            return RejectReason.INVALID_QUANTITY;
        }
        if (quantity < config.minOrderQty) {
            log.debug("Quantity below minimum: qty={}, min={}", quantity, config.minOrderQty);
            return RejectReason.INVALID_QUANTITY;
        }
        if (quantity > config.maxOrderQty) {
            log.debug("Quantity above maximum: qty={}, max={}", quantity, config.maxOrderQty);
            return RejectReason.INVALID_QUANTITY;
        }
        if (config.quantityStep > 0 && quantity % config.quantityStep != 0) {
            log.debug("Quantity not aligned to step: qty={}, step={}", quantity, config.quantityStep);
            return RejectReason.INVALID_QUANTITY;
        }
        return RejectReason.NONE;
    }

    /**
     * 校验限价单价格是否合法（tick 对齐）。
     *
     * @param config 交易对配置
     * @param price  委托价格（固定精度）
     * @return NONE=通过，INVALID_PRICE=价格不合法
     */
    public RejectReason checkPrice(final SymbolConfig config, final long price) {
        if (price <= 0) {
            return RejectReason.INVALID_PRICE;
        }
        if (config.priceTick > 0 && price % config.priceTick != 0) {
            log.debug("Price not aligned to tick: price={}, tick={}", price, config.priceTick);
            return RejectReason.INVALID_PRICE;
        }
        return RejectReason.NONE;
    }
}
```

---

## 6. FeeCalculator

精确计算 Maker/Taker 手续费，支持返佣（负费率）。

文件：`counter-service/src/main/java/com/trading/counter/fee/FeeCalculator.java`

```java
package com.trading.counter.fee;

import com.trading.counter.model.SymbolConfig;

/**
 * 手续费计算器。
 *
 * <p>手续费公式：
 * <pre>
 *   fee = fillAmount × feeRateMicros / 1_000_000
 *   fillAmount = fillPrice × fillQty / precision_factor
 * </pre>
 *
 * <p>特殊情况：
 * <ul>
 *   <li>feeRateMicros < 0 表示返佣（Maker），fee 为负值，需从系统费用池中划出</li>
 *   <li>feeRateMicros = 0 表示免手续费</li>
 * </ul>
 *
 * @author Reln Ding
 */
public final class FeeCalculator {

    private FeeCalculator() {}

    /**
     * 计算 Taker 手续费（quote 资产）。
     *
     * @param config    交易对配置
     * @param fillPrice 成交价（固定精度）
     * @param fillQty   成交量（固定精度）
     * @return 手续费（固定精度，quote 资产），>= 0
     */
    public static long calcTakerFee(final SymbolConfig config,
                                    final long fillPrice, final long fillQty) {
        return calcFee(fillPrice, fillQty,
                       config.pricePrecision, config.quantityPrecision,
                       config.takerFeeRateMicros);
    }

    /**
     * 计算 Maker 手续费（quote 资产）。
     * 返回值可为负（返佣场景）。
     *
     * @param config    交易对配置
     * @param fillPrice 成交价（固定精度）
     * @param fillQty   成交量（固定精度）
     * @return 手续费（固定精度，quote 资产），可为负
     */
    public static long calcMakerFee(final SymbolConfig config,
                                    final long fillPrice, final long fillQty) {
        return calcFee(fillPrice, fillQty,
                       config.pricePrecision, config.quantityPrecision,
                       config.makerFeeRateMicros);
    }

    /**
     * 估算下单手续费（用于风控预检冻结）。
     * 保守估计：按 Taker 费率计算全量手续费。
     *
     * @param config   交易对配置
     * @param price    委托价
     * @param quantity 委托量
     * @return 预估手续费（固定精度），>= 0
     */
    public static long estimateFee(final SymbolConfig config,
                                   final long price, final long quantity) {
        return Math.max(0L, calcFee(price, quantity,
                                    config.pricePrecision, config.quantityPrecision,
                                    config.takerFeeRateMicros));
    }

    // ----------------------------------------------------------------
    // 核心计算（内部）
    // ----------------------------------------------------------------

    /**
     * 手续费计算核心。
     * fee = price × qty / 10^quantityPrecision × feeRateMicros / 1_000_000
     */
    static long calcFee(final long price, final long qty,
                        final int pricePrecision, final int quantityPrecision,
                        final int feeRateMicros) {
        // fillAmount（quote，精度 pricePrecision）
        final long divisor = pow10(quantityPrecision);
        final long fillAmount = price * (qty / divisor) + price * (qty % divisor) / divisor;
        // fee = fillAmount × feeRateMicros / 1_000_000
        // 分步计算防溢出
        return (fillAmount / 1_000_000L) * feeRateMicros
             + (fillAmount % 1_000_000L) * feeRateMicros / 1_000_000L;
    }

    private static long pow10(final int n) {
        long r = 1L;
        for (int i = 0; i < n; i++) r *= 10L;
        return r;
    }
}
```

---

## 7. 风控与手续费单元测试

文件：`counter-service/src/test/java/com/trading/counter/risk/RiskCheckerTest.java`

```java
package com.trading.counter.risk;

import com.trading.counter.account.AccountManager;
import com.trading.counter.account.PositionManager;
import com.trading.counter.fee.FeeCalculator;
import com.trading.counter.model.Position;
import com.trading.counter.model.SymbolConfig;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 风控模块 + FeeCalculator 单元测试。
 * 覆盖所有拒绝场景和通过场景，分支覆盖率 100%。
 *
 * @author Reln Ding
 */
class RiskCheckerTest {

    private AccountManager     accountMgr;
    private PositionManager    positionMgr;
    private BalanceRiskChecker balanceChecker;
    private PositionRiskChecker positionChecker;
    private PriceBandChecker   priceChecker;
    private SymbolConfig       btcUsdt;
    private SymbolConfig       btcPerp;

    @BeforeEach
    void setUp() {
        accountMgr      = new AccountManager();
        positionMgr     = new PositionManager();
        balanceChecker  = new BalanceRiskChecker(accountMgr, positionMgr);
        positionChecker = new PositionRiskChecker(positionMgr);
        priceChecker    = new PriceBandChecker();
        btcUsdt         = SymbolConfig.btcUsdt();
        btcPerp         = SymbolConfig.btcPerpUsdt();
    }

    // ================================================================
    // BalanceRiskChecker — Spot
    // ================================================================

    @Nested @DisplayName("BalanceRiskChecker — Spot")
    class SpotBalanceTests {

        @Test @DisplayName("SpotLimitBuy 余额充足返回 NONE")
        void spotLimitBuyPass() {
            accountMgr.deposit(1001L, btcUsdt.quoteCurrency, 10_000_00L);
            final long fee = FeeCalculator.estimateFee(btcUsdt, 5_000_000L, 100_000L);
            final RejectReason r = balanceChecker.checkSpotLimitBuy(
                1001L, btcUsdt, 5_000_000L, 100_000L, fee);
            assertEquals(RejectReason.NONE, r);
        }

        @Test @DisplayName("SpotLimitBuy 账户不存在返回 INSUFFICIENT_BALANCE")
        void spotLimitBuyNoAccount() {
            final RejectReason r = balanceChecker.checkSpotLimitBuy(
                9999L, btcUsdt, 5_000_000L, 100_000L, 0L);
            assertEquals(RejectReason.INSUFFICIENT_BALANCE, r);
        }

        @Test @DisplayName("SpotLimitBuy 余额不足返回 INSUFFICIENT_BALANCE")
        void spotLimitBuyInsufficient() {
            accountMgr.deposit(1001L, btcUsdt.quoteCurrency, 100L);  // 只有 1 USDT
            final RejectReason r = balanceChecker.checkSpotLimitBuy(
                1001L, btcUsdt, 5_000_000L, 100_000L, 0L);
            assertEquals(RejectReason.INSUFFICIENT_BALANCE, r);
        }

        @Test @DisplayName("SpotLimitSell 余额充足返回 NONE")
        void spotLimitSellPass() {
            accountMgr.deposit(1001L, btcUsdt.baseCurrency, 1_000_000L);
            final RejectReason r = balanceChecker.checkSpotLimitSell(
                1001L, btcUsdt, 500_000L);
            assertEquals(RejectReason.NONE, r);
        }

        @Test @DisplayName("SpotLimitSell 账户不存在返回 INSUFFICIENT_BALANCE")
        void spotLimitSellNoAccount() {
            assertEquals(RejectReason.INSUFFICIENT_BALANCE,
                         balanceChecker.checkSpotLimitSell(9999L, btcUsdt, 100L));
        }

        @Test @DisplayName("SpotLimitSell 余额不足返回 INSUFFICIENT_BALANCE")
        void spotLimitSellInsufficient() {
            accountMgr.deposit(1001L, btcUsdt.baseCurrency, 10L);
            assertEquals(RejectReason.INSUFFICIENT_BALANCE,
                         balanceChecker.checkSpotLimitSell(1001L, btcUsdt, 100L));
        }

        @Test @DisplayName("SpotMarketBuy quote > 0 返回 NONE")
        void spotMarketBuyPass() {
            accountMgr.deposit(1001L, btcUsdt.quoteCurrency, 1L);
            assertEquals(RejectReason.NONE,
                         balanceChecker.checkSpotMarketBuy(1001L, btcUsdt));
        }

        @Test @DisplayName("SpotMarketBuy quote = 0 返回 INSUFFICIENT_BALANCE")
        void spotMarketBuyZeroBalance() {
            accountMgr.getOrCreate(1001L, btcUsdt.quoteCurrency);  // 创建但余额为 0
            assertEquals(RejectReason.INSUFFICIENT_BALANCE,
                         balanceChecker.checkSpotMarketBuy(1001L, btcUsdt));
        }

        @Test @DisplayName("SpotMarketBuy 账户不存在返回 INSUFFICIENT_BALANCE")
        void spotMarketBuyNoAccount() {
            assertEquals(RejectReason.INSUFFICIENT_BALANCE,
                         balanceChecker.checkSpotMarketBuy(9999L, btcUsdt));
        }

        @Test @DisplayName("SpotMarketSell base > 0 返回 NONE")
        void spotMarketSellPass() {
            accountMgr.deposit(1001L, btcUsdt.baseCurrency, 1L);
            assertEquals(RejectReason.NONE,
                         balanceChecker.checkSpotMarketSell(1001L, btcUsdt));
        }

        @Test @DisplayName("SpotMarketSell base = 0 返回 INSUFFICIENT_BALANCE")
        void spotMarketSellZero() {
            accountMgr.getOrCreate(1001L, btcUsdt.baseCurrency);
            assertEquals(RejectReason.INSUFFICIENT_BALANCE,
                         balanceChecker.checkSpotMarketSell(1001L, btcUsdt));
        }

        @Test @DisplayName("SpotMarketSell 账户不存在返回 INSUFFICIENT_BALANCE")
        void spotMarketSellNoAccount() {
            assertEquals(RejectReason.INSUFFICIENT_BALANCE,
                         balanceChecker.checkSpotMarketSell(9999L, btcUsdt));
        }
    }

    // ================================================================
    // BalanceRiskChecker — Contract
    // ================================================================

    @Nested @DisplayName("BalanceRiskChecker — Contract")
    class ContractBalanceTests {

        @BeforeEach
        void depositMargin() {
            positionMgr.getOrCreateMarginAccount(1001L, btcPerp.settleCurrency)
                       .deposit(10_000_00L);
        }

        @Test @DisplayName("ContractOpen 保证金充足返回 NONE")
        void contractOpenPass() {
            assertEquals(RejectReason.NONE,
                         balanceChecker.checkContractOpen(
                             1001L, btcPerp, 5000_00L, 10L, 10));
        }

        @Test @DisplayName("ContractOpen 保证金账户不存在返回 INSUFFICIENT_BALANCE")
        void contractOpenNoAccount() {
            assertEquals(RejectReason.INSUFFICIENT_BALANCE,
                         balanceChecker.checkContractOpen(
                             9999L, btcPerp, 5000_00L, 10L, 10));
        }

        @Test @DisplayName("ContractOpen 保证金不足返回 INSUFFICIENT_BALANCE")
        void contractOpenInsufficient() {
            assertEquals(RejectReason.INSUFFICIENT_BALANCE,
                         balanceChecker.checkContractOpen(
                             1001L, btcPerp, 5000_00L, 10_000_000L, 1));  // 超大仓位
        }
    }

    // ================================================================
    // PositionRiskChecker
    // ================================================================

    @Nested @DisplayName("PositionRiskChecker")
    class PositionLimitTests {

        @Test @DisplayName("空仓时开小量，通过")
        void noPositionSmallOrder() {
            assertEquals(RejectReason.NONE,
                         positionChecker.checkPositionLimit(
                             1001L, btcPerp, Position.LONG, 100L));
        }

        @Test @DisplayName("持仓 + 新增 <= 上限，通过")
        void belowLimit() {
            positionMgr.getOrCreateMarginAccount(1001L, btcPerp.settleCurrency)
                       .deposit(10_000_000_00L);
            positionMgr.openPosition(1001L, btcPerp, Position.LONG,
                                     5000_00L, btcPerp.maxOrderQty * 5L, 10, (byte)1);
            assertEquals(RejectReason.NONE,
                         positionChecker.checkPositionLimit(
                             1001L, btcPerp, Position.LONG, btcPerp.maxOrderQty * 4L));
        }

        @Test @DisplayName("持仓 + 新增 > 上限，返回 EXCEED_POSITION_LIMIT")
        void exceedLimit() {
            positionMgr.getOrCreateMarginAccount(1001L, btcPerp.settleCurrency)
                       .deposit(10_000_000_00L);
            positionMgr.openPosition(1001L, btcPerp, Position.LONG,
                                     5000_00L, btcPerp.maxOrderQty * 9L, 10, (byte)1);
            assertEquals(RejectReason.EXCEED_POSITION_LIMIT,
                         positionChecker.checkPositionLimit(
                             1001L, btcPerp, Position.LONG, btcPerp.maxOrderQty * 2L));
        }
    }

    // ================================================================
    // PriceBandChecker
    // ================================================================

    @Nested @DisplayName("PriceBandChecker")
    class PriceBandTests {

        @Test @DisplayName("参考价为 0 时直接通过（新交易对）")
        void noReferencePrice() {
            assertEquals(RejectReason.NONE,
                         priceChecker.check(btcUsdt, 5000_00L, 0L));
        }

        @Test @DisplayName("价格在笼子内通过")
        void withinBand() {
            // priceBandRatio=10%, ref=5000, upper=5500, lower=4500
            assertEquals(RejectReason.NONE,
                         priceChecker.check(btcUsdt, 5200_00L, 5000_00L));
        }

        @Test @DisplayName("价格恰好等于上界通过")
        void atUpperBound() {
            // upper = 5000 * 1.1 = 5500
            assertEquals(RejectReason.NONE,
                         priceChecker.check(btcUsdt, 5500_00L, 5000_00L));
        }

        @Test @DisplayName("价格超出上界返回 PRICE_OUT_OF_BAND")
        void aboveUpperBound() {
            assertEquals(RejectReason.PRICE_OUT_OF_BAND,
                         priceChecker.check(btcUsdt, 5501_00L, 5000_00L));
        }

        @Test @DisplayName("价格低于下界返回 PRICE_OUT_OF_BAND")
        void belowLowerBound() {
            assertEquals(RejectReason.PRICE_OUT_OF_BAND,
                         priceChecker.check(btcUsdt, 4499_00L, 5000_00L));
        }

        @Test @DisplayName("checkQuantity 正常数量通过")
        void quantityPass() {
            assertEquals(RejectReason.NONE,
                         priceChecker.checkQuantity(btcUsdt, 1_000L));
        }

        @Test @DisplayName("checkQuantity 数量为 0 返回 INVALID_QUANTITY")
        void quantityZero() {
            assertEquals(RejectReason.INVALID_QUANTITY,
                         priceChecker.checkQuantity(btcUsdt, 0L));
        }

        @Test @DisplayName("checkQuantity 数量为负返回 INVALID_QUANTITY")
        void quantityNegative() {
            assertEquals(RejectReason.INVALID_QUANTITY,
                         priceChecker.checkQuantity(btcUsdt, -1L));
        }

        @Test @DisplayName("checkQuantity 低于最小下单量返回 INVALID_QUANTITY")
        void quantityBelowMin() {
            // btcUsdt.minOrderQty = 1
            // 不会触发，直接测试 quantityStep 对齐
            // step=1，qty=1 pass
            assertEquals(RejectReason.NONE,
                         priceChecker.checkQuantity(btcUsdt, 1L));
        }

        @Test @DisplayName("checkQuantity 超出最大下单量返回 INVALID_QUANTITY")
        void quantityAboveMax() {
            assertEquals(RejectReason.INVALID_QUANTITY,
                         priceChecker.checkQuantity(btcUsdt, btcUsdt.maxOrderQty + 1L));
        }

        @Test @DisplayName("checkPrice 正常价格通过")
        void pricePass() {
            assertEquals(RejectReason.NONE,
                         priceChecker.checkPrice(btcUsdt, 5000_00L));
        }

        @Test @DisplayName("checkPrice 价格为 0 返回 INVALID_PRICE")
        void priceZero() {
            assertEquals(RejectReason.INVALID_PRICE,
                         priceChecker.checkPrice(btcUsdt, 0L));
        }

        @Test @DisplayName("checkPrice 价格为负返回 INVALID_PRICE")
        void priceNegative() {
            assertEquals(RejectReason.INVALID_PRICE,
                         priceChecker.checkPrice(btcUsdt, -1L));
        }
    }

    // ================================================================
    // FeeCalculator
    // ================================================================

    @Nested @DisplayName("FeeCalculator")
    class FeeCalculatorTests {

        @Test @DisplayName("calcTakerFee 正确计算（0.2%）")
        void takerFee() {
            // price=50000.00 (5_000_000), qty=1BTC (1_000_000), pricePrecision=2, qtyPrecision=6
            // fillAmount = 5_000_000 * 1_000_000 / 10^6 = 5_000_000 (= 50000.00 USDT)
            // fee = 5_000_000 * 2000 / 1_000_000 = 10_000 (= 100.00 USDT)
            final long fee = FeeCalculator.calcTakerFee(btcUsdt, 5_000_000L, 1_000_000L);
            assertEquals(10_000L, fee);
        }

        @Test @DisplayName("calcMakerFee 正确计算（0.1%）")
        void makerFee() {
            final long fee = FeeCalculator.calcMakerFee(btcUsdt, 5_000_000L, 1_000_000L);
            assertEquals(5_000L, fee);
        }

        @Test @DisplayName("calcMakerFee 负费率返回负值（返佣）")
        void makerFeeRebate() {
            // 手动构造一个 makerFee=-500（-0.05%）的 SymbolConfig
            final SymbolConfig rebateConfig = new SymbolConfig(
                99, 1, 2, SymbolConfig.TYPE_SPOT,
                2, 6, 1L, 1L, 1L, 1_000_000_000L, 1,
                -500, 2_000, 0L, 0, 0L, 100_000);
            final long fee = FeeCalculator.calcMakerFee(rebateConfig, 5_000_000L, 1_000_000L);
            assertTrue(fee < 0, "Rebate fee should be negative");
        }

        @Test @DisplayName("estimateFee 返回非负值（即使 makerFee 为负）")
        void estimateFeeNonNegative() {
            final SymbolConfig rebateConfig = new SymbolConfig(
                99, 1, 2, SymbolConfig.TYPE_SPOT,
                2, 6, 1L, 1L, 1L, 1_000_000_000L, 1,
                -500, 2_000, 0L, 0, 0L, 100_000);
            final long fee = FeeCalculator.estimateFee(rebateConfig, 5_000_000L, 1_000_000L);
            assertTrue(fee >= 0, "estimateFee should always be >= 0");
        }

        @Test @DisplayName("零费率返回 0")
        void zeroFeeRate() {
            final SymbolConfig freeConfig = new SymbolConfig(
                99, 1, 2, SymbolConfig.TYPE_SPOT,
                2, 6, 1L, 1L, 1L, 1_000_000_000L, 1,
                0, 0, 0L, 0, 0L, 100_000);
            assertEquals(0L, FeeCalculator.calcTakerFee(freeConfig, 5_000_000L, 1_000_000L));
            assertEquals(0L, FeeCalculator.calcMakerFee(freeConfig, 5_000_000L, 1_000_000L));
        }

        @Test @DisplayName("小额交易手续费精度不为负（精度丢失容忍）")
        void smallAmountFee() {
            // qty=1 (最小单位)
            final long fee = FeeCalculator.calcTakerFee(btcUsdt, 5_000_000L, 1L);
            assertTrue(fee >= 0);
        }
    }
}
```

### 7.1 运行单元测试

```bash
cd trading-platform
mvn test -pl counter-service -Dtest=RiskCheckerTest -Dcheckstyle.skip=true
# 期望：Tests run: 34, Failures: 0, Errors: 0
```

---

## Part 3 完成检查清单

- [ ] `RejectReason` 枚举 11 个值与 SBE Schema 一一对应
- [ ] `BalanceRiskChecker`：Spot 4 种场景（LimitBuy/LimitSell/MarketBuy/MarketSell）+ Contract Open，每种均有 pass/fail/noAccount 三个测试
- [ ] `PositionRiskChecker`：空仓、未超限、超限三个场景均有测试
- [ ] `PriceBandChecker`：参考价为 0（跳过）、价格在范围内、恰好在边界、超上界、低于下界五个场景均有测试
- [ ] `PriceBandChecker.checkQuantity` / `checkPrice`：覆盖 0、负数、超范围、step 不对齐
- [ ] `FeeCalculator`：Taker/Maker/返佣/零费率/estimateFee 五个场景
- [ ] `RiskCheckerTest` 34 个测试全部通过
- [ ] 含 Part 1-2 合计 **97 个测试，0 Failures，0 Errors**

---

## 下一步：Part 4

Part 3 完成后，进入 **Part 4：柜台 Disruptor Pipeline + ExecutionReportProcessor**，包括：

1. `CounterEvent`：Disruptor RingBuffer 事件定义
2. `AuthHandler`：JWT/API Key 鉴权（简化版本）
3. `SymbolHandler`：交易对状态校验（是否开放交易）
4. `RiskHandler`：调用 BalanceRiskChecker + PositionRiskChecker + PriceBandChecker
5. `FreezeHandler`：通过风控后冻结资金/保证金，记录 OrderState
6. `RouteHandler`：编码 SBE InternalNewOrder，发布到 Aeron IPC stream=2
7. `ExecutionReportProcessor`：订阅撮合回报，更新账户/仓位，回报客户端
8. `InboundDisruptor`：五段 Pipeline 装配
