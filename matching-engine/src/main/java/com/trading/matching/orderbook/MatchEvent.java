package com.trading.matching.orderbook;

/**
 * 单笔成交事件（撮合引擎内部使用）。
 *
 * <p>由 {@link MatchEventList} 预分配并复用，热路径不产生新对象。
 * 撮合完成后，Pipeline 的下游 Handler 读取此列表并分发（回报、行情、日志）。
 *
 * @author Reln Ding
 */
public class MatchEvent {
    /**
     * 撮合全局序列号（由 SequenceAssignHandler 分配）
     */
    public long sequenceNo;

    /**
     * 交易对 ID
     */
    public int symbolId;

    /**
     * Maker 订单 ID（被动方，挂单方）
     */
    public long makerOrderId;

    /**
     * Taker 订单 ID（主动方，进攻方）
     */
    public long takerOrderId;

    /**
     * Maker 账户 ID
     */
    public long makerAccountId;

    /**
     * Taker 账户 ID
     */
    public long takerAccountId;

    /**
     * 成交价格（Maker 报价优先）
     */
    public long price;

    /**
     * 成交数量
     */
    public long quantity;

    /**
     * Maker 买卖方向
     */
    public byte makerSide;

    /**
     * Maker 手续费（固定精度整数，可为负表示返佣）
     */
    public long makerFee;

    /**
     * Taker 手续费
     */
    public long takerFee;

    /**
     * 成交时间戳（纳秒）
     */
    public long timestampNs;

    /**
     * Maker 成交后是否已全部成交（用于通知柜台服务更新状态）
     */
    public boolean makerFullyFilled;

    /**
     * Taker 成交后是否已全部成交
     */
    public boolean takerFullyFilled;

    /**
     * 重置字段（归还列表时调用）
     */
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
