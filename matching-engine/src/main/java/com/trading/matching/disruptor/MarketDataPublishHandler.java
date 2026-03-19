package com.trading.matching.disruptor;

import com.lmax.disruptor.EventHandler;
import com.trading.matching.orderbook.MatchEvent;
import com.trading.matching.orderbook.MatchResult;
import com.trading.sbe.MessageHeaderEncoder;
import com.trading.sbe.OrderBookUpdateEncoder;
import com.trading.sbe.Side;
import io.aeron.Publication;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Stage 3c：行情数据发布器。
 *
 * <p>将成交事件编码为 SBE {@code OrderBookUpdate}（templateId=302）消息，
 * 通过 Aeron IPC stream=4 发送给推送服务（Push Service）用于行情广播。
 *
 * @author Reln Ding
 */
public class MarketDataPublishHandler implements EventHandler<MatchingEvent> {
    private static final Logger log = LoggerFactory.getLogger(MarketDataPublishHandler.class);

    private final Publication marketDataPublication;   // Aeron IPC stream=4
    private final MutableDirectBuffer sendBuffer;
    private final MessageHeaderEncoder headerEncoder;
    private final OrderBookUpdateEncoder obUpdateEncoder;

    public MarketDataPublishHandler(final Publication marketDataPublication) {
        this.marketDataPublication = marketDataPublication;
        this.sendBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
        this.headerEncoder = new MessageHeaderEncoder();
        this.obUpdateEncoder = new OrderBookUpdateEncoder();
    }

    @Override
    public void onEvent(final MatchingEvent event,
                        final long sequence,
                        final boolean endOfBatch) {
        final MatchResult result = event.matchResult;
        for (int i = 0; i < result.events.size(); i++) {
            publishOrderBookUpdate(event.matchSequenceNo, result.events.get(i));
        }
    }

    private void publishOrderBookUpdate(final long matchSeqNo, final MatchEvent e) {
        final int headerLen = MessageHeaderEncoder.ENCODED_LENGTH;
        headerEncoder.wrap(sendBuffer, 0)
                .blockLength(OrderBookUpdateEncoder.BLOCK_LENGTH)
                .templateId(OrderBookUpdateEncoder.TEMPLATE_ID)
                .schemaId(OrderBookUpdateEncoder.SCHEMA_ID)
                .version(OrderBookUpdateEncoder.SCHEMA_VERSION);

        // 推送 Maker 侧深度变化（成交消耗了 Maker 侧的挂单量）
        obUpdateEncoder.wrap(sendBuffer, headerLen)
                .sequenceNo(matchSeqNo)
                .symbolId(e.symbolId)
                .side(Side.get(e.makerSide))
                .price(e.price)
                .quantity(e.makerFullyFilled ? 0L : -e.quantity)  // 0=档位清空，负值=减少量
                .timestamp(e.timestampNs);

        final int totalLen = headerLen + OrderBookUpdateEncoder.BLOCK_LENGTH;
        final long result = marketDataPublication.offer(sendBuffer, 0, totalLen);
        if (result < 0 && result != Publication.BACK_PRESSURED) {
            log.warn("MarketData publish failed: {}, matchSeq={}", result, matchSeqNo);
        }
    }
}
