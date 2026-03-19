package com.trading.matching.disruptor;

import com.lmax.disruptor.EventHandler;
import com.trading.matching.matcher.OrderMatcher;
import com.trading.matching.orderbook.OrderNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stage 2：撮合执行器。
 *
 * <p>处理两类事件：
 * <ul>
 *   <li>eventType=1 (NewOrder)：调用 {@link OrderMatcher#match(OrderNode, com.trading.matching.orderbook.MatchResult)}。</li>
 *   <li>eventType=2 (CancelOrder)：调用 {@link OrderMatcher#cancel(long)}。</li>
 * </ul>
 *
 * <p>单线程执行，所有状态变更（订单簿修改）在此线程完成，无并发问题。
 *
 * @author Reln Ding
 */
public class MatchingHandler implements EventHandler<MatchingEvent> {

    private static final Logger log = LoggerFactory.getLogger(MatchingHandler.class);

    private static final byte EVENT_NEW_ORDER = 1;
    private static final byte EVENT_CANCEL_ORDER = 2;

    private final OrderMatcher matcher;

    public MatchingHandler(final OrderMatcher matcher) {
        this.matcher = matcher;
    }

    @Override
    public void onEvent(MatchingEvent event, long sequence, boolean endOfBatch) throws Exception {
        if (event.eventType == EVENT_NEW_ORDER) {
            handleNewOrder(event);
        } else if (event.eventType == EVENT_CANCEL_ORDER) {
            handleCancelOrder(event);
        } else {
            log.warn("Unknown eventType: {}, sequence: {}", event.eventType, sequence);
        }
    }

    private void handleNewOrder(final MatchingEvent event) {
        final OrderNode taker = event.orderNode;
        if (taker == null) {
            log.warn("NewOrder event has null orderNode, matchSeq={}", event.matchSequenceNo);
            return;
        }
        event.matchResult.reset(taker);
        matcher.match(taker, event.matchResult);
    }

    private void handleCancelOrder(final MatchingEvent event) {
        final OrderNode cancelled = matcher.cancel(event.cancelOrderId);
        event.cancelFound = (cancelled != null);
        event.cancelledNode = cancelled;
    }
}
