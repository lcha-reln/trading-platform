package com.trading.matching.disruptor;

import com.lmax.disruptor.EventHandler;
import com.trading.matching.orderbook.MatchEvent;
import com.trading.matching.orderbook.MatchResult;
import io.aeron.Publication;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Stage 3a：Journal 事件发布器。
 *
 * <p>将成交事件通过 Aeron IPC stream=5 异步写入 Journal Service，
 * 不阻塞撮合热路径。写入失败时记录告警（Journal 允许极小概率丢失，
 * 实际丢失由 Aeron Archive 的 Raft 复制保证）。
 *
 * @author Reln Ding
 */
public class JournalPublishHandler implements EventHandler<MatchingEvent> {
    private static final Logger log = LoggerFactory.getLogger(JournalPublishHandler.class);

    // Journal 事件二进制格式（固定 64 字节/条）
    // Offset 0 : int64 sequenceNo
    // Offset 8 : int32 symbolId
    // Offset 12: int64 makerOrderId
    // Offset 20: int64 takerOrderId
    // Offset 28: int64 makerAccountId
    // Offset 36: int64 takerAccountId
    // Offset 44: int64 price
    // Offset 52: int64 quantity
    // Offset 60: int64 timestampNs
    private static final int JOURNAL_MSG_LENGTH = 68;

    private final Publication journalPublication;
    private final MutableDirectBuffer sendBuffer;

    public JournalPublishHandler(final Publication journalPublication) {
        this.journalPublication = journalPublication;
        this.sendBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(JOURNAL_MSG_LENGTH));
    }

    @Override
    public void onEvent(MatchingEvent event, long sequence, boolean endOfBatch) throws Exception {
        final MatchResult result = event.matchResult;
        for (int i = 0; i < result.events.size(); i++) {
            publishTradeEvent(event.matchSequenceNo, result.events.get(i));
        }

        // 撤单事件也写 Journal（简化：仅记录 sequenceNo + orderId）
        if (event.cancelFound && event.cancelledNode != null) {
            publishCancelEvent(event.matchSequenceNo, event.cancelOrderId);
        }
    }

    private void publishTradeEvent(final long matchSeqNo, final MatchEvent e) {
        sendBuffer.putLong(0, matchSeqNo);
        sendBuffer.putInt(8, e.symbolId);
        sendBuffer.putLong(12, e.makerOrderId);
        sendBuffer.putLong(20, e.takerOrderId);
        sendBuffer.putLong(28, e.makerAccountId);
        sendBuffer.putLong(36, e.takerAccountId);
        sendBuffer.putLong(44, e.price);
        sendBuffer.putLong(52, e.quantity);
        sendBuffer.putLong(60, e.timestampNs);

        final long result = journalPublication.offer(sendBuffer, 0, JOURNAL_MSG_LENGTH);

        if (result < 0 && result != Publication.BACK_PRESSURED) {
            log.warn("Journal publish failed: {}, matchSeq={}", result, matchSeqNo);
        }
    }

    private void publishCancelEvent(final long matchSeqNo, final long orderId) {
        // 简化：仅写入 sequenceNo + orderId（实际可扩展为完整 cancel 事件格式）
        sendBuffer.putLong(0, matchSeqNo);
        sendBuffer.putLong(8, orderId);
        journalPublication.offer(sendBuffer, 0, 16);
    }
}
