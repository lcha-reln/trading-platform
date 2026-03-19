package com.trading.matching.disruptor;

import com.lmax.disruptor.EventHandler;

/**
 * Stage 1：全局撮合序列号分配器。
 *
 * <p>此 Handler 单线程执行，保证序列号严格递增。
 * 序列号是事件日志（Journal）的主键，也是系统全局事件顺序的依据。
 *
 * @author Reln Ding
 */
public class SequenceAssignHandler implements EventHandler<MatchingEvent> {
    private long sequencerCounter = 0L;

    @Override
    public void onEvent(MatchingEvent event, long sequence, boolean endOfBatch) throws Exception {
        event.matchSequenceNo = ++sequencerCounter;
    }

    /**
     * 获取当前已分配的最大序列号（用于监控）
     */
    public long getCurrentSequence() {
        return sequencerCounter;
    }
}
