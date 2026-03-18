package com.trading.util;

/**
 * 纳秒级时间提供器。
 * <p>
 * 为什么不直接用 System.nanoTime()？
 * - 在 Aeron Cluster 中，时间必须来自 Cluster 提供的确定性时钟
 * - 此接口允许在测试/Cluster 模式下替换时间源
 * - 避免在撮合引擎中直接依赖 System.nanoTime()，保持确定性
 */
public interface NanoTimeProvider {
    /**
     * 系统默认实现：使用 System.nanoTime() + 启动时的 epoch 偏移量。
     * 注意：System.nanoTime() 不保证是 UTC epoch，此处做了修正。
     */
    NanoTimeProvider SYSTEM = new NanoTimeProvider() {
        // JVM 启动时记录 epoch 偏移
        private final long epochOffsetNs =
                System.currentTimeMillis() * 1_000_000L - System.nanoTime();

        @Override
        public long nanoTime() {
            return System.nanoTime() - epochOffsetNs;
        }
    };

    /**
     * 返回当前时间，单位：纳秒（UTC epoch）。
     * 实现必须是单调递增的（不要求绝对精确的 UTC，但必须单调）。
     */
    long nanoTime();
}
