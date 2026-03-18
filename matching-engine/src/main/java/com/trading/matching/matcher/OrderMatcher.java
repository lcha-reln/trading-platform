package com.trading.matching.matcher;

import com.trading.matching.orderbook.MatchEvent;
import com.trading.matching.orderbook.MatchResult;
import com.trading.matching.orderbook.OrderBook;
import com.trading.matching.orderbook.OrderNode;
import com.trading.matching.orderbook.PriceLevel;

import java.util.Map;
import java.util.TreeMap;

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
public class OrderMatcher {
    /**
     * 市价买单使用 Long.MAX_VALUE 作为哨兵价格，确保能匹配所有卖价
     */
    public static final long MARKET_BUY_PRICE = Long.MAX_VALUE;

    /**
     * 市价卖单使用 0 作为哨兵价格，确保能匹配所有买价
     */
    public static final long MARKET_SELL_PRICE = 0L;

    /**
     * 手续费率精度：1/1_000_000（百万分之一）
     */
    private static final long FEE_RATE_DIVISOR = 1_000_000L;

    private final OrderBook orderBook;

    /**
     * Maker 手续费率（百万分之一，如 1000 = 0.1%）
     */
    private final int makerFeeRateMicros;

    /**
     * Taker 手续费率
     */
    private final int takerFeeRateMicros;

    /**
     * 纳秒时间提供器（撮合引擎内部使用 Cluster 时钟）
     */
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
    private static long calcFee(final long price, final long quantity, final int feeRateMicros) {
        // 避免 long 溢出：先除后乘
        return (price / FEE_RATE_DIVISOR) * quantity * feeRateMicros
                + (price % FEE_RATE_DIVISOR) * quantity * feeRateMicros / FEE_RATE_DIVISOR;
    }

    /**
     * 对新订单执行撮合。
     *
     * <p>调用方须在调用前：
     * <ol>
     *   <li>从对象池借出 OrderNode 并初始化。</li>
     *   <li>调用 {@link MatchResult#reset(OrderNode)}。</li>
     * </ol>
     * <p>
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

    // ================================================================
    // Limit 限价单
    // ================================================================

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
    // Market 市价单
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
            result.takerStatus = MatchResult.TakerStatus.RESTING;
        } else {
            result.takerStatus = MatchResult.TakerStatus.FULLY_FILLED;
        }
    }

    // ================================================================
    // IOC 立即成交或撤销
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
            result.takerStatus = taker.filledQty > 0 ? MatchResult.TakerStatus.PARTIAL_FILL_CANCELLED : MatchResult.TakerStatus.CANCELLED;
        } else {
            result.takerStatus = MatchResult.TakerStatus.FULLY_FILLED;
        }
    }

    // ================================================================
    // FOK 全成交或全撤
    // ================================================================

    /**
     * IOC 撮合：能成交多少就成交多少，剩余立即撤销。
     */
    private void matchIoc(final OrderNode taker, final MatchResult result) {
        executeCoreMatch(taker, result);

        if (taker.leavesQty > 0) {
            result.takerStatus = taker.filledQty > 0
                    ? MatchResult.TakerStatus.PARTIAL_FILL_CANCELLED
                    : MatchResult.TakerStatus.CANCELLED;
        } else {
            result.takerStatus = MatchResult.TakerStatus.FULLY_FILLED;
        }
    }

    /**
     * FOK 撮合：先预检流动性，足够则全量成交，不足则全部拒绝（零成交）。
     *
     * <p>预检阶段只遍历价格档位，不修改任何状态。
     */
    private void matchFok(final OrderNode taker, final MatchResult result) {
        if (!canFillCompletely(taker)) {
            // 流动性不足，直接拒绝，不产生任何成交
            result.takerStatus = MatchResult.TakerStatus.REJECTED;
            return;
        }

        // 流动性充足，执行完整撮合
        executeCoreMatch(taker, result);
        // FOK 撮合后 leavesQty 必须为 0（由预检保证）
        result.takerStatus = MatchResult.TakerStatus.FULLY_FILLED;
    }

    // ================================================================
    // PostOnly 仅挂单
    // ================================================================

    /**
     * FOK 流动性预检：遍历对手盘档位，判断是否能完全满足 Taker 数量。
     *
     * <p>只读操作，不修改订单簿。
     */
    private boolean canFillCompletely(final OrderNode taker) {
        long remaining = taker.quantity;
        final TreeMap<Long, PriceLevel> opposuitSide = taker.isBuy() ? orderBook.getAsks() : orderBook.getBids();

        for (final Map.Entry<Long, PriceLevel> entry : opposuitSide.entrySet()) {
            final long leverPrice = entry.getKey();

            if (taker.isBuy() && leverPrice > taker.price) break; // 卖价超出买价上限
            if (taker.isSell() && leverPrice < taker.price) break; // 买价低于卖价下限

            remaining -= Math.min(remaining, entry.getValue().totalQty);

            if (remaining == 0) {
                return true;
            }
        }

        return false;
    }

    // ================================================================
    // 核心撮合循环（Price-Time Priority）
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
            result.takerStatus = MatchResult.TakerStatus.CANCELLED;
        } else {
            orderBook.addOrder(taker);
            result.takerStatus = MatchResult.TakerStatus.RESTING;
        }
    }

    // ================================================================
    // 手续费计算
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
        final TreeMap<Long, PriceLevel> oppositeSide = taker.isBuy() ? orderBook.getAsks() : orderBook.getBids();

        while (taker.leavesQty > 0 && !oppositeSide.isEmpty()) {
            final Map.Entry<Long, PriceLevel> bestEntry = oppositeSide.firstEntry();
            final long bestPrice = bestEntry.getKey();

            // 价格匹配检查
            if (taker.isBuy() && bestPrice > taker.price) break;  // 最低卖价 > 买单价格
            if (taker.isSell() && bestPrice < taker.price) break;  // 最高买价 < 卖单价格

            final PriceLevel level = bestEntry.getValue();
            OrderNode maker = level.head;

            while (maker != null && taker.leavesQty > 0) {
                final long fillQty = Math.min(taker.leavesQty, maker.leavesQty);
                final long fillPrice = maker.price;
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
}
