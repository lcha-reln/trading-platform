package com.trading.matching.orderbook;

import com.trading.sbe.OrderType;
import com.trading.sbe.Side;

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
public class OrderNode {
    // ---- 订单基础字段（由柜台服务写入，撮合引擎只读）----

    /**
     * 系统全局唯一订单 ID（Snowflake 生成）
     */
    public long orderId;

    /**
     * 账户 ID
     */
    public long accountId;

    /**
     * 交易对 ID
     */
    public int symbolId;

    /**
     * 买卖方向
     */
    public byte side;           // Side.BUY = 1, Side.SELL = 2

    /**
     * 订单类型
     */
    public byte orderType;      // OrderType.LIMIT = 1, MARKET = 2, ...

    /**
     * 有效时间类型
     */
    public byte timeInForce;    // TimeInForce.GTC = 1, GTD = 2, GFD = 3

    /**
     * 委托价格（固定精度整数，Market 单为 Long.MAX_VALUE 或 0）
     */
    public long price;

    /**
     * 原始委托数量（固定精度整数）
     */
    public long quantity;

    /**
     * 已成交数量
     */
    public long filledQty;

    /**
     * 剩余未成交数量 = quantity - filledQty
     */
    public long leavesQty;

    /**
     * GTD 订单过期时间（纳秒 UTC epoch；非 GTD 订单填 0）
     */
    public long expireTimeNs;

    /**
     * 订单进入撮合引擎的时间戳（纳秒），用于 Time Priority 排序标记
     */
    public long acceptTimestampNs;

    // ---- 双向链表指针（由 PriceLevel 管理）----

    /**
     * 同价位链表中的前驱节点（时间更早）
     */
    public OrderNode prev;

    /**
     * 同价位链表中的后继节点（时间更晚）
     */
    public OrderNode next;

    // ---- 生命周期方法 ----

    /**
     * 初始化节点字段（从对象池借出后调用）。
     * 所有字段显式赋值，防止复用脏数据。
     */
    public void init(final long orderId,
                     final long accountId,
                     final int symbolId,
                     final byte side,
                     final byte orderType,
                     final byte timeInForce,
                     final long price,
                     final long quantity,
                     final long expireTimeNs,
                     final long acceptTimestampNs) {
        this.orderId = orderId;
        this.accountId = accountId;
        this.symbolId = symbolId;
        this.side = side;
        this.orderType = orderType;
        this.timeInForce = timeInForce;
        this.price = price;
        this.quantity = quantity;
        this.filledQty = 0L;
        this.leavesQty = quantity;
        this.expireTimeNs = expireTimeNs;
        this.acceptTimestampNs = acceptTimestampNs;
        this.prev = null;
        this.next = null;
    }

    /**
     * 归还对象池前重置，防止持有外部引用导致内存泄漏。
     */
    public void reset() {
        this.orderId = 0L;
        this.accountId = 0L;
        this.symbolId = 0;
        this.side = 0;
        this.orderType = 0;
        this.timeInForce = 0;
        this.price = 0L;
        this.quantity = 0L;
        this.filledQty = 0L;
        this.leavesQty = 0L;
        this.expireTimeNs = 0L;
        this.acceptTimestampNs = 0L;
        this.prev = null;
        this.next = null;
    }

    /**
     * 是否已全部成交
     */
    public boolean isFilled() {
        return leavesQty == 0L;
    }

    /**
     * 是否为买单
     */
    public boolean isBuy() {
        return side == Side.BUY.value();
    }

    /**
     * 是否为卖单
     */
    public boolean isSell() {
        return side == Side.SELL.value();
    }

    /**
     * 是否为限价单
     */
    public boolean isLimit() {
        return orderType == OrderType.LIMIT.value();
    }

    /**
     * 是否为市价单
     */
    public boolean isMarket() {
        return orderType == OrderType.MARKET.value();
    }

    /**
     * 是否为 IOC
     */
    public boolean isIoc() {
        return orderType == OrderType.IOC.value();
    }

    /**
     * 是否为 FOK
     */
    public boolean isFok() {
        return orderType == OrderType.FOK.value();
    }

    /**
     * 是否为 PostOnly
     */
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
