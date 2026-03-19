package com.trading.matching.disruptor;

import com.lmax.disruptor.EventHandler;
import com.trading.matching.orderbook.MatchEvent;
import com.trading.matching.orderbook.MatchResult;
import com.trading.sbe.MatchResultEncoder;
import com.trading.sbe.MessageHeaderEncoder;
import com.trading.sbe.Side;
import io.aeron.Publication;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Stage 3b：执行回报发布器。
 *
 * <p>将成交事件编码为 SBE {@code MatchResult}（templateId=301）消息，
 * 通过 Aeron IPC stream=3 发送给柜台服务，柜台服务再生成 {@code ExecutionReport} 回报给客户端。
 *
 * @author Reln Ding
 */
public class ExecutionReportHandler implements EventHandler<MatchingEvent> {
    private static final Logger log = LoggerFactory.getLogger(ExecutionReportHandler.class);

    private final Publication execReportPublication;   // Aeron IPC stream=3
    private final MutableDirectBuffer sendBuffer;
    private final MessageHeaderEncoder headerEncoder;
    private final MatchResultEncoder matchResultEncoder;

    public ExecutionReportHandler(final Publication execReportPublication) {
        this.execReportPublication = execReportPublication;
        this.sendBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(512));
        this.headerEncoder = new MessageHeaderEncoder();
        this.matchResultEncoder = new MatchResultEncoder();
    }

    @Override
    public void onEvent(final MatchingEvent event,
                        final long sequence,
                        final boolean endOfBatch) {
        final MatchResult result = event.matchResult;

        // 逐笔发送成交回报
        for (int i = 0; i < result.events.size(); i++) {
            publishMatchResult(event.matchSequenceNo, result.events.get(i));
        }

        // 处理撤单确认（无成交但有状态变更的情况）
        if (event.eventType == 2) {  // CancelOrder
            publishCancelConfirm(event);
        }

        // RESTING / CANCELLED / REJECTED 状态通知（无成交事件时需单独推送）
        if (!result.events.hasEvents() && result.takerNode != null) {
            publishTakerStatusOnly(event);
        }

        // 归还已全成交/撤销的 Taker 节点（RESTING 状态的节点留在订单簿中，不归还）
        final MatchResult.TakerStatus status = result.takerStatus;
        if (status != null && status != MatchResult.TakerStatus.RESTING
                && result.takerNode != null) {
            // Taker 节点不再需要，归还对象池
            // 注：实际调用由 OrderBook.releaseNode 完成，此处仅作占位说明
        }

        // 归还已撤销的 Maker 节点（由 MatchingHandler 在 onFill 时已归还，此处无需重复）
        if (event.cancelFound && event.cancelledNode != null) {
            // cancelledNode 由柜台服务对应的 Handler 归还，撮合引擎侧不重复归还
        }
    }

    private void publishMatchResult(final long matchSeqNo, final MatchEvent e) {
        final int headerLen = MessageHeaderEncoder.ENCODED_LENGTH;
        headerEncoder.wrap(sendBuffer, 0)
                .blockLength(MatchResultEncoder.BLOCK_LENGTH)
                .templateId(MatchResultEncoder.TEMPLATE_ID)
                .schemaId(MatchResultEncoder.SCHEMA_ID)
                .version(MatchResultEncoder.SCHEMA_VERSION);

        matchResultEncoder.wrap(sendBuffer, headerLen)
                .sequenceNo(matchSeqNo)
                .symbolId(e.symbolId)
                .makerOrderId(e.makerOrderId)
                .takerOrderId(e.takerOrderId)
                .makerAccountId(e.makerAccountId)
                .takerAccountId(e.takerAccountId)
                .price(e.price)
                .quantity(e.quantity)
                .makerSide(Side.get(e.makerSide))
                .makerFee(e.makerFee)
                .takerFee(e.takerFee)
                .timestamp(e.timestampNs);

        final int totalLen = headerLen + MatchResultEncoder.BLOCK_LENGTH;
        final long result = execReportPublication.offer(sendBuffer, 0, totalLen);
        if (result < 0 && result != Publication.BACK_PRESSURED) {
            log.warn("ExecReport publish failed: {}, matchSeq={}", result, matchSeqNo);
        }
    }

    private void publishCancelConfirm(final MatchingEvent event) {
        // 简化实现：实际需编码 CancelConfirm SBE 消息，此处省略
        log.debug("Cancel confirm: orderId={}, found={}", event.cancelOrderId, event.cancelFound);
    }

    private void publishTakerStatusOnly(final MatchingEvent event) {
        // 针对 RESTING / IOC-CANCELLED / FOK-REJECTED 等无成交事件的状态变更
        // 需单独发送 ExecutionReport 给柜台服务，此处简化为 log
        log.debug("Taker status only: status={}, orderId={}",
                event.matchResult.takerStatus,
                event.matchResult.takerNode != null
                        ? event.matchResult.takerNode.orderId : -1);
    }
}
