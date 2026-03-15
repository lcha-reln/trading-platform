package com.trading.util;

/**
 * Snowflake 变体订单 ID 生成器。
 * <p>
 * 64 位结构：
 *   [63]       符号位，永远为 0
 *   [62..21]   42 bit 毫秒时间戳（可用约 139 年）
 *   [20..16]   5  bit 节点 ID（0~31，对应 Aeron Cluster 成员 ID）
 *   [15..0]    16 bit 序列号（每毫秒最多 65535 个 ID）
 * <p>
 * 设计要点：
 *   - 单线程使用（撮合引擎/柜台均为单线程调度），无锁
 *   - 生成 ID 单调递增，天然有序，有利于 HashMap/TreeMap 性能
 *   - 节点 ID 区分不同 Cluster 节点，防止多节点冲突
 */
public final class SnowflakeIdGenerator {
    // 各字段位数
    private static final int NODE_ID_BITS    = 5;
    private static final int SEQUENCE_BITS   = 16;

    // 最大值掩码
    private static final long MAX_NODE_ID    = (1L << NODE_ID_BITS) - 1;   // 31
    private static final long MAX_SEQUENCE   = (1L << SEQUENCE_BITS) - 1;  // 65535

    // 左移量
    private static final int  NODE_ID_SHIFT  = SEQUENCE_BITS;               // 16
    private static final int  TIMESTAMP_SHIFT = NODE_ID_BITS + SEQUENCE_BITS; // 21

    // 自定义纪元（2024-01-01 00:00:00 UTC，减小时间戳值）
    private static final long EPOCH_MS = 1704067200000L;

    private final long nodeId;
    private long lastTimestampMs = -1L;
    private long sequence = 0L;

    /**
     * @param nodeId 节点 ID，范围 [0, 31]，对应 Aeron Cluster 成员 ID
     */
    public SnowflakeIdGenerator(final int nodeId) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException(
                    "nodeId must be in [0, " + MAX_NODE_ID + "], got: " + nodeId);
        }

        this.nodeId = nodeId;
    }

    /**
     * 生成下一个唯一 ID。
     * 注意：此方法非线程安全，只允许单线程调用。
     *
     * @return 64 位唯一单调递增 ID
     */
    public long nextId() {
        // Snowflake ID 是一个 64 位的唯一 ID，其中一部分存储时间戳
        // 但 Unix epoch（1970年）距离现在已有50多年，数值很大，会浪费 ID 的位数
        // 所以通常选择一个更近的时刻作为 epoch（比如 2015-01-01），这样时间戳数值更小，能在同样的位数内存储更长时间跨度的 ID
        long currentMs = System.currentTimeMillis() - EPOCH_MS;

        if (currentMs == lastTimestampMs) {
            // 同一毫秒内，序列号递增，模运算（取余）且保证结果在 16 位范围内
            sequence = (sequence + 1) & MAX_SEQUENCE;

            if (sequence == 0) {
                // 同一毫秒序列号溢出，等待下一毫秒
                currentMs = waitNextMillis(lastTimestampMs);
            }
        } else if (currentMs > lastTimestampMs) {
            // 新毫秒，重置序列号
            sequence = 0;
        } else {
            // 时钟回拨（NTP 调整等），抛出异常防止 ID 重复
            throw new IllegalStateException(
                    "Clock moved backwards! lastMs=" + lastTimestampMs +
                            ", currentMs=" + currentMs);
        }

        lastTimestampMs = currentMs;

        // 时间戳左移 21 位，节点 ID 左移 16 位，sequence 无需左移
        return (currentMs << TIMESTAMP_SHIFT)
                | (nodeId << NODE_ID_SHIFT)
                | sequence;
    }

    private long waitNextMillis(final long lastMs) {
        long ms;

        do {
            ms = System.currentTimeMillis() -  EPOCH_MS;
        } while (ms <= lastMs);

        return ms;
    }
}
