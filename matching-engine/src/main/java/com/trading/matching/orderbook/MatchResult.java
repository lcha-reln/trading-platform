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
public class MatchResult {
    /**
     * 本次撮合产生的成交事件列表
     */
    public final MatchEventList events;
    /**
     * Taker 最终状态
     */
    public TakerStatus takerStatus;
    /**
     * Taker 订单节点引用（用于下游生成回报消息）
     */
    public OrderNode takerNode;

    public MatchResult(final int eventListCapacity) {
        this.events = new MatchEventList(eventListCapacity);
    }

    /**
     * 重置（每次撮合开始前调用）
     */
    public void reset(final OrderNode taker) {
        events.clear();
        takerStatus = null;
        takerNode = taker;
    }

    /**
     * Taker 订单的最终处置结果。
     */
    public enum TakerStatus {
        /**
         * Taker 挂入订单簿（有剩余数量，GTC 语义）
         */
        RESTING,
        /**
         * Taker 全部成交
         */
        FULLY_FILLED,
        /**
         * Taker 部分成交后被撤销（IOC 语义）
         */
        PARTIAL_FILL_CANCELLED,
        /**
         * Taker 零成交被撤销（FOK 预检失败 / IOC 无流动性 / PostOnly 有成交）
         */
        CANCELLED,
        /**
         * Taker 被拒绝（FOK 预检失败，使用此状态区分于普通撤销）
         */
        REJECTED
    }
}
