# Phase 3 柜台服务实现 — Part 2：AccountManager + PositionManager

> **目标：** 实现账户余额管理器和仓位管理器，提供 Spot 下单冻结/解冻/结算，
> 以及合约开平仓、均价维护、强平价格计算的完整业务逻辑。
>
> **前置条件：** Part 1 领域模型 38 个测试通过  
> **本节验证目标：** AccountManager + PositionManager 单元测试全部通过

---

## 目录

1. [SymbolConfigManager](#1-symbolconfigmanager)
2. [AccountManager（现货余额管理）](#2-accountmanager现货余额管理)
3. [PositionManager（合约仓位管理）](#3-positionmanager合约仓位管理)
4. [单元测试](#4-单元测试)

---

## 1. SymbolConfigManager

管理所有交易对配置，提供注册、查询、状态变更接口。

文件：`counter-service/src/main/java/com/trading/counter/symbol/SymbolConfigManager.java`

```java
package com.trading.counter.symbol;

import com.trading.counter.model.SymbolConfig;
import org.agrona.collections.Int2ObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 交易对配置管理器。
 *
 * <p>所有配置在 Aeron Cluster 启动时通过快照恢复，运行期可通过管理指令热更新状态。
 * 使用 Agrona Int2ObjectHashMap 避免装箱。
 *
 * @author Reln Ding
 */
public final class SymbolConfigManager {

    private static final Logger log = LoggerFactory.getLogger(SymbolConfigManager.class);

    private final Int2ObjectHashMap<SymbolConfig> configs = new Int2ObjectHashMap<>(64, 0.6f);

    /**
     * 注册交易对配置。
     *
     * @param config 交易对配置（不可为 null）
     */
    public void register(final SymbolConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("SymbolConfig must not be null");
        }
        configs.put(config.symbolId, config);
        log.info("Registered symbol: symbolId={}, type={}, status={}",
                 config.symbolId, config.symbolType, config.status);
    }

    /**
     * 查询交易对配置。
     *
     * @param symbolId 交易对 ID
     * @return 配置对象，或 {@code null}（不存在）
     */
    public SymbolConfig get(final int symbolId) {
        return configs.get(symbolId);
    }

    /**
     * 变更交易对状态（热更新）。
     *
     * @param symbolId  交易对 ID
     * @param newStatus 新状态（参见 SymbolConfig.STATUS_* 常量）
     * @return true=成功，false=交易对不存在
     */
    public boolean updateStatus(final int symbolId, final byte newStatus) {
        final SymbolConfig config = configs.get(symbolId);
        if (config == null) {
            return false;
        }
        config.status = newStatus;
        log.info("Symbol status updated: symbolId={}, status={}", symbolId, newStatus);
        return true;
    }

    /** 是否存在该交易对 */
    public boolean contains(final int symbolId) {
        return configs.containsKey(symbolId);
    }

    /** 已注册交易对数量 */
    public int size() {
        return configs.size();
    }
}
```

---

## 2. AccountManager（现货余额管理）

管理所有账户所有资产的余额，提供下单冻结、撤单解冻、成交结算三个核心操作。

文件：`counter-service/src/main/java/com/trading/counter/account/AccountManager.java`

```java
package com.trading.counter.account;

import com.trading.counter.model.AccountBalance;
import com.trading.counter.model.SymbolConfig;
import org.agrona.collections.Long2ObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 现货账户余额管理器。
 *
 * <p>内存结构：accountId → assetId → AccountBalance（二级 HashMap）。
 * 使用 Agrona Long2ObjectHashMap 和内层 int[] key HashMap 避免装箱。
 *
 * <p>线程安全：非线程安全，由 Disruptor 单线程调用。
 *
 * @author Reln Ding
 */
public final class AccountManager {

    private static final Logger log = LoggerFactory.getLogger(AccountManager.class);

    /**
     * accountId → (assetId → AccountBalance)。
     * 内层 map 用 Long2ObjectHashMap 模拟 int key（assetId 转 long 存储）。
     */
    private final Long2ObjectHashMap<Long2ObjectHashMap<AccountBalance>> accounts
        = new Long2ObjectHashMap<>(1024, 0.6f);

    // ----------------------------------------------------------------
    // 账户 / 余额管理
    // ----------------------------------------------------------------

    /**
     * 获取或创建账户余额（懒创建）。
     */
    public AccountBalance getOrCreate(final long accountId, final int assetId) {
        Long2ObjectHashMap<AccountBalance> assets = accounts.get(accountId);
        if (assets == null) {
            assets = new Long2ObjectHashMap<>(8, 0.6f);
            accounts.put(accountId, assets);
        }
        AccountBalance bal = assets.get(assetId);
        if (bal == null) {
            bal = new AccountBalance(accountId, assetId);
            assets.put(assetId, bal);
        }
        return bal;
    }

    /**
     * 查询余额（不创建）。
     *
     * @return AccountBalance，或 {@code null}（账户/资产不存在）
     */
    public AccountBalance get(final long accountId, final int assetId) {
        final Long2ObjectHashMap<AccountBalance> assets = accounts.get(accountId);
        return assets == null ? null : assets.get(assetId);
    }

    /**
     * 充值（充入资金）。
     */
    public void deposit(final long accountId, final int assetId, final long amount) {
        getOrCreate(accountId, assetId).deposit(amount);
    }

    // ----------------------------------------------------------------
    // Spot 下单冻结
    // ----------------------------------------------------------------

    /**
     * 现货限价买单冻结：冻结 quote 资产。
     *
     * <p>冻结金额 = price × quantity / 10^(pricePrecision+quantityPrecision) + fee_estimate
     * 简化：冻结金额 = price × quantity（精度处理由调用方换算好后传入）
     *
     * @param accountId   账户 ID
     * @param config      交易对配置
     * @param price       委托价（固定精度 long）
     * @param quantity    委托量（固定精度 long）
     * @param feeEstimate 预估手续费（固定精度 long，quote 资产）
     * @return true=冻结成功，false=余额不足
     */
    public boolean freezeForLimitBuy(final long accountId, final SymbolConfig config,
                                     final long price, final long quantity,
                                     final long feeEstimate) {
        // 需要冻结的 quote 金额 = price * quantity / precision_factor + fee
        final long quoteRequired = calcQuoteAmount(price, quantity,
                                                   config.pricePrecision,
                                                   config.quantityPrecision)
                                   + feeEstimate;
        final AccountBalance quoteBal = getOrCreate(accountId, config.quoteCurrency);
        final boolean ok = quoteBal.freeze(quoteRequired);
        if (!ok) {
            log.debug("freezeForLimitBuy failed: accountId={}, available={}, required={}",
                      accountId, quoteBal.getAvailable(), quoteRequired);
        }
        return ok;
    }

    /**
     * 现货限价卖单冻结：冻结 base 资产。
     *
     * @param accountId 账户 ID
     * @param config    交易对配置
     * @param quantity  委托量（固定精度 long）
     * @return true=冻结成功，false=余额不足
     */
    public boolean freezeForLimitSell(final long accountId, final SymbolConfig config,
                                      final long quantity) {
        final AccountBalance baseBal = getOrCreate(accountId, config.baseCurrency);
        final boolean ok = baseBal.freeze(quantity);
        if (!ok) {
            log.debug("freezeForLimitSell failed: accountId={}, available={}, qty={}",
                      accountId, baseBal.getAvailable(), quantity);
        }
        return ok;
    }

    // ----------------------------------------------------------------
    // 撤单解冻
    // ----------------------------------------------------------------

    /**
     * 撤单解冻（现货买单）：解冻 quote 资产。
     *
     * @param accountId    账户 ID
     * @param config       交易对配置
     * @param frozenAmount 当时冻结的金额（由 OrderState 记录）
     */
    public void unfreezeForCancelBuy(final long accountId, final SymbolConfig config,
                                     final long frozenAmount) {
        if (frozenAmount <= 0) return;
        getOrCreate(accountId, config.quoteCurrency).unfreeze(frozenAmount);
    }

    /**
     * 撤单解冻（现货卖单）：解冻 base 资产。
     *
     * @param accountId    账户 ID
     * @param config       交易对配置
     * @param frozenAmount 当时冻结的数量
     */
    public void unfreezeForCancelSell(final long accountId, final SymbolConfig config,
                                      final long frozenAmount) {
        if (frozenAmount <= 0) return;
        getOrCreate(accountId, config.baseCurrency).unfreeze(frozenAmount);
    }

    // ----------------------------------------------------------------
    // 成交结算（Spot）
    // ----------------------------------------------------------------

    /**
     * 现货成交结算（买方）。
     *
     * <p>买方结算规则：
     * <ul>
     *   <li>quote 账户：从冻结中扣除实际成交金额 + 手续费</li>
     *   <li>base 账户：增加到 available</li>
     * </ul>
     *
     * @param accountId    买方账户 ID
     * @param config       交易对配置
     * @param fillPrice    成交价（固定精度）
     * @param fillQty      成交量（固定精度）
     * @param takerFee     手续费（固定精度，quote 资产，已含在冻结金额内）
     */
    public void settleBuyFill(final long accountId, final SymbolConfig config,
                              final long fillPrice, final long fillQty,
                              final long takerFee) {
        final long quoteCost = calcQuoteAmount(fillPrice, fillQty,
                                              config.pricePrecision,
                                              config.quantityPrecision);
        // quote 账户：从冻结扣除成交金额 + 手续费
        getOrCreate(accountId, config.quoteCurrency).settle(quoteCost + takerFee, 0L);
        // base 账户：available 增加成交量
        getOrCreate(accountId, config.baseCurrency).settle(0L, fillQty);
    }

    /**
     * 现货成交结算（卖方）。
     *
     * <p>卖方结算规则：
     * <ul>
     *   <li>base 账户：从冻结中扣除成交量</li>
     *   <li>quote 账户：available 增加成交金额 - 手续费</li>
     * </ul>
     *
     * @param accountId    卖方账户 ID
     * @param config       交易对配置
     * @param fillPrice    成交价
     * @param fillQty      成交量
     * @param takerFee     手续费（quote 资产）
     */
    public void settleSellFill(final long accountId, final SymbolConfig config,
                               final long fillPrice, final long fillQty,
                               final long takerFee) {
        final long quoteReceived = calcQuoteAmount(fillPrice, fillQty,
                                                   config.pricePrecision,
                                                   config.quantityPrecision);
        // base 账户：冻结扣除成交量
        getOrCreate(accountId, config.baseCurrency).settle(fillQty, 0L);
        // quote 账户：available 增加净收款
        getOrCreate(accountId, config.quoteCurrency).settle(0L, quoteReceived - takerFee);
    }

    // ----------------------------------------------------------------
    // 精度换算（内部）
    // ----------------------------------------------------------------

    /**
     * 计算成交金额（quote 数量）。
     * amount = price × quantity / 10^(pricePrecision + quantityPrecision)
     * 等价于：amount_long = price_long × quantity_long / 10^quantityPrecision
     * （因为 price_long 已含 pricePrecision 精度，结果精度 = pricePrecision）
     */
    static long calcQuoteAmount(final long price, final long quantity,
                                final int pricePrecision, final int quantityPrecision) {
        // 避免 long 溢出：price * qty / 10^quantityPrecision
        final long divisor = pow10(quantityPrecision);
        return price * (quantity / divisor)
             + price * (quantity % divisor) / divisor;
    }

    private static long pow10(final int n) {
        long result = 1L;
        for (int i = 0; i < n; i++) result *= 10L;
        return result;
    }
}
```

---

## 3. PositionManager（合约仓位管理）

管理所有账户所有交易对的合约仓位，提供开平仓、均价维护、强平价格计算。

文件：`counter-service/src/main/java/com/trading/counter/account/PositionManager.java`

```java
package com.trading.counter.account;

import com.trading.counter.model.MarginAccount;
import com.trading.counter.model.Position;
import com.trading.counter.model.SymbolConfig;
import org.agrona.collections.Long2ObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 合约仓位管理器。
 *
 * <p>内存结构：
 * <ul>
 *   <li>positions：accountId → symbolId*10+side → Position</li>
 *   <li>marginAccounts：accountId → currency → MarginAccount</li>
 * </ul>
 *
 * @author Reln Ding
 */
public final class PositionManager {

    private static final Logger log = LoggerFactory.getLogger(PositionManager.class);

    /** accountId → posKey(symbolId*10+side) → Position */
    private final Long2ObjectHashMap<Long2ObjectHashMap<Position>> positions
        = new Long2ObjectHashMap<>(256, 0.6f);

    /** accountId → currency → MarginAccount */
    private final Long2ObjectHashMap<Long2ObjectHashMap<MarginAccount>> marginAccounts
        = new Long2ObjectHashMap<>(256, 0.6f);

    // ----------------------------------------------------------------
    // 查询
    // ----------------------------------------------------------------

    /**
     * 获取仓位（不存在返回 null）。
     *
     * @param accountId 账户 ID
     * @param symbolId  交易对 ID
     * @param side      方向（Position.LONG / Position.SHORT）
     */
    public Position getPosition(final long accountId, final int symbolId, final byte side) {
        final Long2ObjectHashMap<Position> posMap = positions.get(accountId);
        return posMap == null ? null : posMap.get(posKey(symbolId, side));
    }

    /**
     * 获取或创建仓位。
     */
    public Position getOrCreatePosition(final long accountId, final int symbolId,
                                        final byte side, final int leverage,
                                        final byte marginMode) {
        Long2ObjectHashMap<Position> posMap = positions.get(accountId);
        if (posMap == null) {
            posMap = new Long2ObjectHashMap<>(8, 0.6f);
            positions.put(accountId, posMap);
        }
        final long key = posKey(symbolId, side);
        Position pos = posMap.get(key);
        if (pos == null) {
            pos = new Position(accountId, symbolId, side, leverage, marginMode);
            posMap.put(key, pos);
        }
        return pos;
    }

    /**
     * 获取或创建保证金账户。
     */
    public MarginAccount getOrCreateMarginAccount(final long accountId, final int currency) {
        Long2ObjectHashMap<MarginAccount> maMap = marginAccounts.get(accountId);
        if (maMap == null) {
            maMap = new Long2ObjectHashMap<>(4, 0.6f);
            marginAccounts.put(accountId, maMap);
        }
        MarginAccount ma = maMap.get(currency);
        if (ma == null) {
            ma = new MarginAccount(accountId, currency);
            maMap.put(currency, ma);
        }
        return ma;
    }

    public MarginAccount getMarginAccount(final long accountId, final int currency) {
        final Long2ObjectHashMap<MarginAccount> maMap = marginAccounts.get(accountId);
        return maMap == null ? null : maMap.get(currency);
    }

    // ----------------------------------------------------------------
    // 开仓
    // ----------------------------------------------------------------

    /**
     * 合约开仓（保证金预检由风控层完成，此处直接执行）。
     *
     * @param accountId   账户 ID
     * @param config      交易对配置
     * @param side        仓位方向
     * @param tradePrice  成交价
     * @param tradeQty    成交量（合约张数）
     * @param leverage    杠杆倍数
     * @param marginMode  保证金模式
     * @return true=开仓成功，false=保证金不足（二次校验）
     */
    public boolean openPosition(final long accountId, final SymbolConfig config,
                                final byte side, final long tradePrice, final long tradeQty,
                                final int leverage, final byte marginMode) {
        // 计算保证金需求
        final long notional = calcNotional(tradePrice, tradeQty, config);
        final long im       = notional / leverage;                    // 初始保证金
        final long mm       = notional * config.takerFeeRateMicros
                              / 1_000_000L;                           // 维持保证金（简化为手续费率倍数）

        final MarginAccount ma = getOrCreateMarginAccount(accountId, config.settleCurrency);
        if (!ma.reserveMargin(im, mm)) {
            log.debug("openPosition failed: insufficient margin, accountId={}, required={}",
                      accountId, im);
            return false;
        }

        final Position pos = getOrCreatePosition(accountId, config.symbolId, side,
                                                 leverage, marginMode);
        pos.open(tradePrice, tradeQty, im, mm);
        updateLiquidationPrice(pos, ma, config);
        return true;
    }

    // ----------------------------------------------------------------
    // 平仓
    // ----------------------------------------------------------------

    /**
     * 合约平仓。
     *
     * @param accountId  账户 ID
     * @param config     交易对配置
     * @param side       平仓方向（= 持仓方向）
     * @param tradePrice 成交价
     * @param tradeQty   平仓量
     * @return 已实现盈亏（已计入钱包余额），或 Long.MIN_VALUE（仓位不存在）
     */
    public long closePosition(final long accountId, final SymbolConfig config,
                              final byte side, final long tradePrice, final long tradeQty) {
        final Position pos = getPosition(accountId, config.symbolId, side);
        if (pos == null || pos.isEmpty()) {
            log.warn("closePosition: no position found, accountId={}, symbolId={}, side={}",
                     accountId, config.symbolId, side);
            return Long.MIN_VALUE;
        }
        if (tradeQty > pos.getQuantity()) {
            log.warn("closePosition: qty {} > position qty {}", tradeQty, pos.getQuantity());
            return Long.MIN_VALUE;
        }

        // 按比例释放保证金
        final long ratio        = tradeQty * 1_000_000L / pos.getQuantity();
        final long releaseIM    = pos.getInitialMargin() * ratio / 1_000_000L;
        final long releaseMM    = pos.getMaintenanceMargin() * ratio / 1_000_000L;

        final long realizedPnl = pos.close(tradeQty, tradePrice, releaseIM, releaseMM);

        // 结算：保证金账户 realizedPnl 计入钱包余额
        final MarginAccount ma = getOrCreateMarginAccount(accountId, config.settleCurrency);
        ma.closeSettle(realizedPnl, releaseIM, releaseMM);

        if (!pos.isEmpty()) {
            updateLiquidationPrice(pos, ma, config);
        }
        return realizedPnl;
    }

    // ----------------------------------------------------------------
    // 盈亏更新
    // ----------------------------------------------------------------

    /**
     * 按最新成交价更新所有持仓的未实现盈亏。
     * 实际生产中应按标记价格计算，此处简化为最新成交价。
     *
     * @param symbolId   交易对 ID
     * @param markPrice  最新成交/标记价格
     */
    public void updateUnrealizedPnl(final int symbolId, final long markPrice) {
        positions.forEach((accountId, posMap) ->
            posMap.forEach((key, pos) -> {
                if (pos.getSymbolId() == symbolId && !pos.isEmpty()) {
                    pos.updateUnrealizedPnl(markPrice);
                    // 同步更新保证金账户的 unrealizedPnl（全仓模式下汇总）
                }
            })
        );
    }

    // ----------------------------------------------------------------
    // 强平价格计算
    // ----------------------------------------------------------------

    /**
     * 计算并更新强平价格。
     *
     * <p>简化公式（逐仓模式）：
     * <pre>
     *   多头强平价 = entryPrice × (1 - 1/leverage + maintenanceMarginRate)
     *   空头强平价 = entryPrice × (1 + 1/leverage - maintenanceMarginRate)
     * </pre>
     * maintenanceMarginRate = mm / notional（近似为维持保证金率）
     */
    private void updateLiquidationPrice(final Position pos,
                                        final MarginAccount ma,
                                        final SymbolConfig config) {
        if (pos.isEmpty()) return;
        final long entry    = pos.getEntryPrice();
        final int  lev      = pos.getLeverage();
        // 维持保证金率 ≈ takerFeeRate（简化）
        final long mmRateMicros = config.takerFeeRateMicros;

        final long liqPrice;
        if (pos.getSide() == Position.LONG) {
            // 多头：价格下跌到强平
            liqPrice = entry - entry / lev + entry * mmRateMicros / 1_000_000L;
        } else {
            // 空头：价格上涨到强平
            liqPrice = entry + entry / lev - entry * mmRateMicros / 1_000_000L;
        }
        pos.setLiquidationPrice(Math.max(0L, liqPrice));
    }

    // ----------------------------------------------------------------
    // 工具
    // ----------------------------------------------------------------

    private static long posKey(final int symbolId, final byte side) {
        return (long) symbolId * 10L + side;
    }

    private static long calcNotional(final long price, final long qty,
                                     final SymbolConfig config) {
        // notional = price × qty × contractSize / precision
        return price * qty * config.contractSize / 1_000_000L;
    }
}
```

---

## 4. 单元测试

文件：`counter-service/src/test/java/com/trading/counter/account/AccountManagerTest.java`

```java
package com.trading.counter.account;

import com.trading.counter.model.AccountBalance;
import com.trading.counter.model.SymbolConfig;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AccountManager 单元测试。
 *
 * @author Reln Ding
 */
class AccountManagerTest {

    private AccountManager mgr;
    private SymbolConfig   btcUsdt;

    @BeforeEach
    void setUp() {
        mgr     = new AccountManager();
        btcUsdt = SymbolConfig.btcUsdt();
        // 充值：账户 1001 持有 100,000.00 USDT
        mgr.deposit(1001L, btcUsdt.quoteCurrency, 100_000_00L);
        // 充值：账户 1002 持有 2 BTC（qty precision=6，long=2_000_000）
        mgr.deposit(1002L, btcUsdt.baseCurrency, 2_000_000L);
    }

    @Test @DisplayName("getOrCreate 自动创建账户余额")
    void getOrCreateAutoCreates() {
        final AccountBalance bal = mgr.getOrCreate(9999L, 99);
        assertNotNull(bal);
        assertEquals(0L, bal.getAvailable());
    }

    @Test @DisplayName("get 不存在时返回 null")
    void getReturnsNullIfAbsent() {
        assertNull(mgr.get(9999L, 99));
    }

    // ---- 限价买单冻结 ----

    @Test @DisplayName("freezeForLimitBuy 成功：quote available 减少，frozen 增加")
    void freezeForLimitBuySuccess() {
        // price=50000.00 (long=5_000_000), qty=0.1BTC (long=100_000), fee=1000
        // quoteAmount = 5_000_000 * 100_000 / 10^6 = 500_000 (= 5000.00 USDT)
        final boolean ok = mgr.freezeForLimitBuy(1001L, btcUsdt, 5_000_000L, 100_000L, 1_000L);
        assertTrue(ok);
        final AccountBalance quoteBal = mgr.get(1001L, btcUsdt.quoteCurrency);
        assertNotNull(quoteBal);
        assertEquals(501_000L, quoteBal.getFrozen());    // 500_000 + fee 1000
        assertEquals(100_000_00L - 501_000L, quoteBal.getAvailable());
    }

    @Test @DisplayName("freezeForLimitBuy 余额不足返回 false")
    void freezeForLimitBuyInsufficient() {
        // 大额买单超出余额
        assertFalse(mgr.freezeForLimitBuy(1001L, btcUsdt, 5_000_000L, 100_000_000L, 0L));
    }

    // ---- 限价卖单冻结 ----

    @Test @DisplayName("freezeForLimitSell 成功：base available 减少")
    void freezeForLimitSellSuccess() {
        // 卖 1 BTC（qty long=1_000_000）
        assertTrue(mgr.freezeForLimitSell(1002L, btcUsdt, 1_000_000L));
        final AccountBalance baseBal = mgr.get(1002L, btcUsdt.baseCurrency);
        assertNotNull(baseBal);
        assertEquals(1_000_000L, baseBal.getAvailable());
        assertEquals(1_000_000L, baseBal.getFrozen());
    }

    @Test @DisplayName("freezeForLimitSell 余额不足返回 false")
    void freezeForLimitSellInsufficient() {
        assertFalse(mgr.freezeForLimitSell(1002L, btcUsdt, 999_000_000L));
    }

    // ---- 撤单解冻 ----

    @Test @DisplayName("unfreezeForCancelBuy 解冻 quote 资产")
    void unfreezeForCancelBuy() {
        mgr.freezeForLimitBuy(1001L, btcUsdt, 5_000_000L, 100_000L, 0L);
        final long frozen = mgr.get(1001L, btcUsdt.quoteCurrency).getFrozen();
        mgr.unfreezeForCancelBuy(1001L, btcUsdt, frozen);
        assertEquals(0L, mgr.get(1001L, btcUsdt.quoteCurrency).getFrozen());
        assertEquals(100_000_00L, mgr.get(1001L, btcUsdt.quoteCurrency).getAvailable());
    }

    @Test @DisplayName("unfreezeForCancelBuy amount<=0 时无操作")
    void unfreezeForCancelBuyZeroAmount() {
        // 不抛异常，不修改状态
        assertDoesNotThrow(() -> mgr.unfreezeForCancelBuy(1001L, btcUsdt, 0L));
    }

    @Test @DisplayName("unfreezeForCancelSell 解冻 base 资产")
    void unfreezeForCancelSell() {
        mgr.freezeForLimitSell(1002L, btcUsdt, 500_000L);
        mgr.unfreezeForCancelSell(1002L, btcUsdt, 500_000L);
        assertEquals(0L, mgr.get(1002L, btcUsdt.baseCurrency).getFrozen());
    }

    // ---- 成交结算 ----

    @Test @DisplayName("settleBuyFill：quote 冻结扣减，base available 增加")
    void settleBuyFill() {
        // 先冻结
        final long price = 5_000_000L;   // 50000.00 USDT
        final long qty   = 100_000L;     // 0.1 BTC
        final long fee   = 1_000L;
        mgr.freezeForLimitBuy(1001L, btcUsdt, price, qty, fee);

        mgr.settleBuyFill(1001L, btcUsdt, price, qty, fee);

        final AccountBalance quoteBal = mgr.get(1001L, btcUsdt.quoteCurrency);
        assertEquals(0L, quoteBal.getFrozen());

        final AccountBalance baseBal = mgr.getOrCreate(1001L, btcUsdt.baseCurrency);
        assertEquals(100_000L, baseBal.getAvailable());  // 收到 0.1 BTC
    }

    @Test @DisplayName("settleSellFill：base 冻结扣减，quote available 增加（扣手续费）")
    void settleSellFill() {
        final long price = 5_000_000L;
        final long qty   = 1_000_000L;   // 1 BTC
        final long fee   = 10_000L;      // 手续费
        mgr.freezeForLimitSell(1002L, btcUsdt, qty);

        mgr.settleSellFill(1002L, btcUsdt, price, qty, fee);

        final AccountBalance baseBal = mgr.get(1002L, btcUsdt.baseCurrency);
        assertEquals(0L, baseBal.getFrozen());  // base 冻结清零

        final AccountBalance quoteBal = mgr.getOrCreate(1002L, btcUsdt.quoteCurrency);
        // quoteReceived = 5_000_000 * 1_000_000 / 10^6 = 5_000_000 (50000.00 USDT)
        // net = 5_000_000 - 10_000 = 4_990_000
        assertEquals(4_990_000L, quoteBal.getAvailable());
    }

    // ---- calcQuoteAmount 精度验证 ----

    @Test @DisplayName("calcQuoteAmount 精度计算正确")
    void calcQuoteAmount() {
        // price=50000.25 (精度0.01 → long=5_000_025)
        // qty=0.123456 BTC (精度0.000001 → long=123_456)
        // quoteAmount ≈ 5000025 * 123456 / 10^6 = 617,283.086... ≈ 617_283
        final long result = AccountManager.calcQuoteAmount(5_000_025L, 123_456L, 2, 6);
        // 验证结果在合理范围内（误差 < 1 单位）
        assertTrue(result >= 617_283L && result <= 617_284L,
                   "Expected ~617283, got " + result);
    }
}
```

文件：`counter-service/src/test/java/com/trading/counter/account/PositionManagerTest.java`

```java
package com.trading.counter.account;

import com.trading.counter.model.MarginAccount;
import com.trading.counter.model.Position;
import com.trading.counter.model.SymbolConfig;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PositionManager 单元测试。
 *
 * @author Reln Ding
 */
class PositionManagerTest {

    private PositionManager mgr;
    private SymbolConfig    btcPerp;

    @BeforeEach
    void setUp() {
        mgr     = new PositionManager();
        btcPerp = SymbolConfig.btcPerpUsdt();
        // 充值 10000 USDT 保证金
        mgr.getOrCreateMarginAccount(1001L, btcPerp.settleCurrency).deposit(10_000_00L);
    }

    @Test @DisplayName("openPosition 成功后仓位数量和均价正确")
    void openPositionSuccess() {
        final boolean ok = mgr.openPosition(1001L, btcPerp, Position.LONG,
                                            5000_00L, 10L, 10, (byte) 1);
        assertTrue(ok);
        final Position pos = mgr.getPosition(1001L, btcPerp.symbolId, Position.LONG);
        assertNotNull(pos);
        assertEquals(10L, pos.getQuantity());
        assertEquals(5000_00L, pos.getEntryPrice());
        assertFalse(pos.isEmpty());
    }

    @Test @DisplayName("openPosition 保证金不足返回 false")
    void openPositionInsufficientMargin() {
        // 大量开仓超出保证金
        final boolean ok = mgr.openPosition(1001L, btcPerp, Position.LONG,
                                            5000_00L, 100_000L, 1, (byte) 1);
        assertFalse(ok);
        assertNull(mgr.getPosition(1001L, btcPerp.symbolId, Position.LONG));
    }

    @Test @DisplayName("openPosition 加仓更新均价")
    void openPositionAddToExisting() {
        mgr.openPosition(1001L, btcPerp, Position.LONG, 5000_00L, 10L, 10, (byte) 1);
        mgr.openPosition(1001L, btcPerp, Position.LONG, 6000_00L, 10L, 10, (byte) 1);
        final Position pos = mgr.getPosition(1001L, btcPerp.symbolId, Position.LONG);
        assertNotNull(pos);
        assertEquals(20L, pos.getQuantity());
        assertEquals(5500_00L, pos.getEntryPrice());  // (5000*10 + 6000*10)/20
    }

    @Test @DisplayName("closePosition 成功平仓，返回正确 PnL")
    void closePositionSuccess() {
        mgr.openPosition(1001L, btcPerp, Position.LONG, 5000_00L, 10L, 10, (byte) 1);

        final long pnl = mgr.closePosition(1001L, btcPerp, Position.LONG, 6000_00L, 10L);
        // pnl = (6000-5000) * 10 = 10000
        assertEquals(1000_00L * 10L, pnl);
        final Position pos = mgr.getPosition(1001L, btcPerp.symbolId, Position.LONG);
        assertNotNull(pos);
        assertTrue(pos.isEmpty());
    }

    @Test @DisplayName("closePosition 仓位不存在返回 Long.MIN_VALUE")
    void closePositionNotFound() {
        final long result = mgr.closePosition(1001L, btcPerp, Position.LONG, 5000_00L, 1L);
        assertEquals(Long.MIN_VALUE, result);
    }

    @Test @DisplayName("closePosition 平仓量超过持仓返回 Long.MIN_VALUE")
    void closePositionExceedsQty() {
        mgr.openPosition(1001L, btcPerp, Position.LONG, 5000_00L, 5L, 10, (byte) 1);
        final long result = mgr.closePosition(1001L, btcPerp, Position.LONG, 6000_00L, 100L);
        assertEquals(Long.MIN_VALUE, result);
        assertEquals(5L, mgr.getPosition(1001L, btcPerp.symbolId, Position.LONG).getQuantity());
    }

    @Test @DisplayName("部分平仓后仓位数量正确减少")
    void partialClosePosition() {
        mgr.openPosition(1001L, btcPerp, Position.LONG, 5000_00L, 10L, 10, (byte) 1);
        mgr.closePosition(1001L, btcPerp, Position.LONG, 5500_00L, 4L);
        final Position pos = mgr.getPosition(1001L, btcPerp.symbolId, Position.LONG);
        assertNotNull(pos);
        assertEquals(6L, pos.getQuantity());
    }

    @Test @DisplayName("closePosition 亏损时 realizedPnl 为负，钱包余额减少")
    void closePositionLoss() {
        mgr.openPosition(1001L, btcPerp, Position.LONG, 5000_00L, 10L, 10, (byte) 1);
        final long pnl = mgr.closePosition(1001L, btcPerp, Position.LONG, 4000_00L, 10L);
        assertTrue(pnl < 0, "Loss position should have negative PnL");
        final MarginAccount ma = mgr.getMarginAccount(1001L, btcPerp.settleCurrency);
        assertNotNull(ma);
        // 钱包余额应减少
        assertTrue(ma.getWalletBalance() < 10_000_00L);
    }

    @Test @DisplayName("getOrCreateMarginAccount 懒创建")
    void getOrCreateMarginAccount() {
        final MarginAccount ma = mgr.getOrCreateMarginAccount(9999L, 2);
        assertNotNull(ma);
        assertEquals(9999L, ma.accountId);
        assertEquals(2, ma.currency);
    }

    @Test @DisplayName("getMarginAccount 不存在时返回 null")
    void getMarginAccountReturnsNull() {
        assertNull(mgr.getMarginAccount(9999L, 999));
    }
}
```

### 4.1 运行单元测试

```bash
cd trading-platform
mvn test -pl counter-service \
  -Dtest="AccountManagerTest,PositionManagerTest" \
  -Dcheckstyle.skip=true
# 期望：Tests run: 25, Failures: 0, Errors: 0
```

---

## Part 2 完成检查清单

- [ ] `SymbolConfigManager`：register / get / updateStatus / contains 均可用
- [ ] `AccountManager.freezeForLimitBuy`：冻结金额 = quoteAmount + fee；余额不足返回 false
- [ ] `AccountManager.freezeForLimitSell`：冻结 base 数量；余额不足返回 false
- [ ] `AccountManager.settleBuyFill`：quote 冻结扣减 + base available 增加
- [ ] `AccountManager.settleSellFill`：base 冻结扣减 + quote available 净增（扣手续费）
- [ ] `AccountManager.calcQuoteAmount`：精度计算无溢出
- [ ] `PositionManager.openPosition`：保证金不足返回 false；加仓均价正确更新
- [ ] `PositionManager.closePosition`：仓位不存在/超量返回 MIN_VALUE；PnL 符号正确
- [ ] `PositionManager.updateLiquidationPrice`：多/空强平价格公式正确
- [ ] `AccountManagerTest` 12 个测试通过，`PositionManagerTest` 9 个测试通过
- [ ] 含 Part 1 合计 **63 个测试，0 Failures，0 Errors**

---

## 下一步：Part 3

Part 2 完成后，进入 **Part 3：风控模块 + FeeCalculator**，包括：

1. `BalanceRiskChecker`：现货/合约余额充足性校验（覆盖 Spot Buy/Sell + Perp Open/Close）
2. `PositionRiskChecker`：仓位上限校验（per-account, per-symbol）
3. `PriceBandChecker`：价格笼子（防异常价格冲击市场）
4. `FeeCalculator`：Maker/Taker 手续费精确计算（含返佣）
5. 风控拒绝场景 100% 单测覆盖
