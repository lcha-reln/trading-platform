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
public class OrderBook {
    /**
     * 交易对 ID
     */
    public final int symbolId;

    /**
     * 买盘价格档位：价格降序（最优买价在首位）
     */
    // 注：生产环境可换为 Agrona LongTreeMap；此处用 JDK TreeMap 降低依赖复杂度
    private final TreeMap<Long, PriceLevel> bids;

    /**
     * 卖盘价格档位：价格升序（最优卖价在首位）
     */
    private final TreeMap<Long, PriceLevel> asks;

    /**
     * 订单快速索引：orderId → OrderNode。
     * 用于 O(1) 撤单查找。
     */
    private final Long2ObjectHashMap<OrderNode> orderIndex;

    /**
     * OrderNode 对象池（由外部注入，多个 OrderBook 可共享同一个池）
     */
    private final OrderNodePool nodePool;

    // ---- 统计字段 ----

    /**
     * 最新成交价
     */
    private long lastTradePrice;

    /**
     * 最新成交量
     */
    private long lastTradeQty;

    /**
     * 24 小时成交量（累计，需定时重置）
     */
    private long volume24h;

    /**
     * 24 小时成交额（累计）
     */
    private long turnover24h;

    /**
     * 当前簿中挂单总数
     */
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
        final OrderNode node = orderIndex.get(orderId);
        if (node == null) {
            return null;    // 订单不存在
        }

        final TreeMap<Long, PriceLevel> side = node.isBuy() ? bids : asks;
        final PriceLevel level = side.get(node.price);
        if (level != null) {
            level.removeOrder(node);
            if (level.isEmpty()) {
                side.remove(node.price);   // 档位空了则清除，防止内存泄漏
            }
        }

        orderIndex.remove(orderId);
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
            if (isFull) {
                // removeOrder 内部已包含 totalQty 扣减，无需再调 reduceQty
                level.removeOrder(node);
                orderIndex.remove(node.orderId);
                totalOrderCount--;

                if (level.isEmpty()) {
                    side.remove(node.price);
                }
            } else {
                level.reduceQty(filledQty);
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

    /**
     * 买盘是否为空
     */
    public boolean isBidEmpty() {
        return bids.isEmpty();
    }

    /**
     * 卖盘是否为空
     */
    public boolean isAskEmpty() {
        return asks.isEmpty();
    }

    /**
     * 当前簿中挂单总数
     */
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
