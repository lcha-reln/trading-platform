package com.trading.matching.disruptor;

import com.lmax.disruptor.EventFactory;
import com.trading.matching.orderbook.MatchResult;
import com.trading.matching.orderbook.OrderNode;

/**
 * Disruptor RingBuffer 事件对象（预分配，反复复用）。
 *
 * <p>生命周期：
 * <ol>
 *   <li>{@link InboundOrderSubscriber} 填充入站字段（orderId、price、qty 等）。</li>
 *   <li>Stage 1 {@link SequenceAssignHandler} 填充 {@code matchSequenceNo}。</li>
 *   <li>Stage 2 {@link MatchingHandler} 执行撮合，结果写入 {@code matchResult}。</li>
 *   <li>Stage 3 各 Handler 读取 {@code matchResult} 并分发。</li>
 * </ol>
 *
 * @author Reln Ding
 */
public class MatchingEvent {
    // ---- 入站字段（由 InboundOrderSubscriber 填充）----
    /**
     * 工厂：Disruptor 启动时调用，预分配所有 Event 对象
     */
    public static final EventFactory<MatchingEvent> FACTORY = MatchingEvent::new;
    /**
     * 撮合结果（预分配，每次 reset 后复用）
     */
    public final MatchResult matchResult;
    /**
     * 事件类型：1=NewOrder, 2=CancelOrder
     */
    public byte eventType;
    /**
     * 订单节点（NewOrder 时从对象池借出；CancelOrder 时为 null）
     */
    public OrderNode orderNode;

    // ---- Stage 1 填充 ----
    /**
     * 撤单时的订单 ID（CancelOrder 专用）
     */
    public long cancelOrderId;

    // ---- Stage 2 填充 ----
    /**
     * 对应请求的 correlationId（用于回报路由）
     */
    public long correlationId;

    // ---- 标志位 ----
    /**
     * 撮合全局序列号（单调递增）
     */
    public long matchSequenceNo;
    /**
     * 撤单操作找到了目标订单（CancelOrder 时有效）
     */
    public boolean cancelFound;
    /**
     * 被撤销的节点（CancelOrder 时有效，Stage 3 用于归还对象池）
     */
    public OrderNode cancelledNode;

    public MatchingEvent() {
        // 每个 Event 预分配一个 MatchResult（含 MatchEventList）
        // 容量 2048：单笔撮合最多产生 2048 笔成交（极端市价单扫穿场景）
        this.matchResult = new MatchResult(2048);
    }

    /**
     * 重置（每次事件被生产者填充前，由生产者负责重置必要字段）
     */
    public void reset() {
        eventType = 0;
        orderNode = null;
        cancelOrderId = 0L;
        correlationId = 0L;
        matchSequenceNo = 0L;
        cancelFound = false;
        cancelledNode = null;
        matchResult.reset(null);
    }
}
