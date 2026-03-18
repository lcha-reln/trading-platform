package com.trading.matching.orderbook;

/**
 * 可复用的成交事件列表（每次撮合复用同一实例）。
 *
 * <p>容量上限 = 单笔撮合最多可产生的成交笔数。
 * 实际场景中，一笔市价单最多扫穿 N 个价格档位，
 * 建议初始容量 = 最大档位扫穿数 × 2（留余量），如 2048。
 *
 * @author Reln Ding
 */
public class MatchEventList {
    private final MatchEvent[] events;
    private int size;

    public MatchEventList(final int capacity) {
        this.events = new MatchEvent[capacity];

        for (int i = 0; i < capacity; i++) {
            events[i] = new MatchEvent();
        }

        this.size = 0;
    }

    /**
     * 申请下一个可写入的 MatchEvent 槽位。
     *
     * @return 可写入的 MatchEvent（已重置），或 {@code null}（已满）
     */
    public MatchEvent next() {
        if (size >= events.length) {
            return null;    // 不应发生，可在此处告警
        }

        final MatchEvent event = events[size++];
        event.reset();  // 分配之前先清空
        return event;
    }

    /**
     * 重置列表（每次新撮合开始前调用）
     */
    public void clear() {
        size = 0;
    }

    /**
     * 已产生的成交事件数量
     */
    public int size() {
        return size;
    }

    /**
     * 是否有成交事件
     */
    public boolean hasEvents() {
        return size > 0;
    }

    /**
     * 按索引读取成交事件（只读）
     */
    public MatchEvent get(final int index) {
        return events[index];
    }
}
