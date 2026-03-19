package com.trading.matching.aeron;

import com.lmax.disruptor.RingBuffer;
import com.trading.matching.disruptor.MatchingEvent;
import com.trading.matching.orderbook.OrderBook;
import com.trading.matching.orderbook.OrderNode;
import com.trading.sbe.InternalCancelOrderDecoder;
import com.trading.sbe.InternalNewOrderDecoder;
import com.trading.sbe.MessageHeaderDecoder;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入站订单订阅器：Aeron IPC stream=2 → Disruptor RingBuffer。
 *
 * <p>运行在独立线程，BusySpin 轮询 Aeron Subscription，
 * 收到消息后解码 SBE 并发布到 RingBuffer。
 *
 * <p>支持消息类型：
 * <ul>
 *   <li>{@code InternalNewOrder}（templateId=201）</li>
 *   <li>{@code InternalCancelOrder}（templateId=202）</li>
 * </ul>
 *
 * @author Reln Ding
 */
public class InboundOrderSubscriber implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(InboundOrderSubscriber.class);

    private static final int FRAGMENT_LIMIT = 10;

    private final Subscription inboundSubscription;
    private final RingBuffer<MatchingEvent> ringBuffer;
    private final OrderBook orderBook;
    private final IdleStrategy idleStrategy;

    // SBE 解码器（预分配，复用）
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final InternalNewOrderDecoder newOrderDecoder = new InternalNewOrderDecoder();
    private final InternalCancelOrderDecoder cancelDecoder = new InternalCancelOrderDecoder();

    private volatile boolean running = true;

    public InboundOrderSubscriber(final Subscription inboundSubscription,
                                  final RingBuffer<MatchingEvent> ringBuffer,
                                  final OrderBook orderBook) {
        this.inboundSubscription = inboundSubscription;
        this.ringBuffer = ringBuffer;
        this.orderBook = orderBook;
        this.idleStrategy = new BusySpinIdleStrategy();
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        final FragmentHandler handler = this::onFragment;

        while (running) {
            final int fragments = inboundSubscription.poll(handler, FRAGMENT_LIMIT);
            idleStrategy.idle(fragments);
        }
    }

    private void onFragment(final DirectBuffer buffer,
                            final int offset,
                            final int length,
                            final Header header) {
        // 读取消息头，判断类型
        headerDecoder.wrap(buffer, offset);
        final int templateId = headerDecoder.templateId();
        final int headerLen = MessageHeaderDecoder.ENCODED_LENGTH;

        if (templateId == InternalNewOrderDecoder.TEMPLATE_ID) {
            handleNewOrder(buffer, offset + headerLen);
        } else if (templateId == InternalCancelOrderDecoder.TEMPLATE_ID) {
            handleCancelOrder(buffer, offset + headerLen);
        } else {
            log.warn("Unknown templateId: {}", templateId);
        }
    }

    private void handleNewOrder(final DirectBuffer buffer, final int bodyOffset) {
        newOrderDecoder.wrap(buffer, bodyOffset,
                InternalNewOrderDecoder.BLOCK_LENGTH,
                InternalNewOrderDecoder.SCHEMA_VERSION);

        // 从对象池借出 OrderNode
        final OrderNode node = orderBook.borrowNode();
        if (node == null) {
            log.error("OrderNodePool exhausted! Dropping order orderId={}",
                    newOrderDecoder.orderId());
            return;
        }

        node.init(
                newOrderDecoder.orderId(),
                newOrderDecoder.accountId(),
                orderBook.symbolId,
                newOrderDecoder.side().value(),
                newOrderDecoder.orderType().value(),
                newOrderDecoder.timeInForce().value(),
                newOrderDecoder.price(),
                newOrderDecoder.quantity(),
                0L,   // expireTimeNs（GTD 扩展时填入）
                newOrderDecoder.timestamp()
        );

        // 发布到 RingBuffer
        ringBuffer.publishEvent((event, seq) -> {
            event.reset();
            event.eventType = 1;
            event.orderNode = node;
            event.correlationId = newOrderDecoder.correlationId();
        });
    }

    private void handleCancelOrder(final DirectBuffer buffer, final int bodyOffset) {
        cancelDecoder.wrap(buffer, bodyOffset,
                InternalCancelOrderDecoder.BLOCK_LENGTH,
                InternalCancelOrderDecoder.SCHEMA_VERSION);

        final long orderId = cancelDecoder.orderId();
        final long correlationId = cancelDecoder.correlationId();

        ringBuffer.publishEvent((event, seq) -> {
            event.reset();
            event.eventType = 2;
            event.cancelOrderId = orderId;
            event.correlationId = correlationId;
        });
    }
}
