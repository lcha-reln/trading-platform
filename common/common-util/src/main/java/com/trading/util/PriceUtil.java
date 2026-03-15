package com.trading.util;

/**
 * 价格和数量精度工具类。
 * <p>
 * 系统约定：
 *   - 所有价格和数量在系统内部以 long 存储，代表固定精度整数
 *   - 精度因子（scale）存储在交易对配置（SymbolConfig）中
 *   - 例如：BTC/USDT pricePrecision=2，价格 50000.25 存为 5000025L
 * <p>
 * 为什么用 long 而不是 BigDecimal？
 *   - BigDecimal 每次运算都创建新对象，产生 GC 压力
 *   - long 运算是 CPU 原生指令，比 BigDecimal 快 10~100 倍
 *   - 固定精度能精确表示十进制小数，无浮点误差
 */
public class PriceUtil {
    // 预计算精度因子表（10^0 ~ 10^18），避免重复计算
    private static final long[] POW10 = new long[19];

    static {
        POW10[0] = 1L;
        for (int i = 1; i < POW10.length; i++) {
            POW10[i] = POW10[i - 1] * 10L;
        }
    }

    private PriceUtil() {}

    /**
     * double → long（用于从外部输入转换，仅在非热路径使用）
     *
     * @param value     double 价格值，如 50000.25
     * @param precision 精度位数，如 2（表示最小单位 0.01）
     * @return long 表示，如 5000025L
     */
    public static long toLong(final double value, final int precision) {
        return Math.round(value * POW10[precision]);
    }

    /**
     * long → double（用于展示/日志，仅在非热路径使用）
     *
     * @param value     long 价格值，如 5000025L
     * @param precision 精度位数，如 2
     * @return double 表示，如 50000.25
     */
    public static double toDouble(final long value, final int precision) {
        return (double) value / POW10[precision];
    }

    /**
     * 计算成交金额（热路径可用）。
     *
     * amount = price × quantity / 10^(pricePrecision + quantityPrecision)
     *
     * @param price              long 价格
     * @param quantity           long 数量
     * @param pricePrecision     价格精度
     * @param quantityPrecision  数量精度
     * @param resultPrecision    期望返回值的精度
     * @return long 成交金额
     */
    public static long calcAmount(final long price,
                                  final long quantity,
                                  final int  pricePrecision,
                                  final int  quantityPrecision,
                                  final int  resultPrecision) {
        // 使用 128 位中间值防止溢出（Java 没有 uint128，用两步除法）
        // price * quantity 可能超过 long，需小心
        // 简化版本（适用于 price/quantity 不超过 10^12 的场景）：
        return Math.multiplyHigh(price, quantity) == 0
                ? (price * quantity * POW10[resultPrecision]) / POW10[pricePrecision + quantityPrecision]
                : (long) ((double) price * quantity / POW10[pricePrecision + quantityPrecision - resultPrecision]);
    }

    /**
     * 计算手续费（热路径可用）。
     *
     * fee = amount × feeRate / 10^feeRatePrecision
     *
     * feeRate 约定：以 10^-6 为单位（百万分之一）
     *   例如：0.1% = 1000（即 1000 / 1_000_000 = 0.001）
     *         0.01% = 100
     *
     * @param amount           成交金额（long）
     * @param feeRateMicros    手续费率（单位 1/1_000_000）
     * @param amountPrecision  成交金额精度
     * @return long 手续费（与 amount 同精度）
     */
    public static long calcFee(final long amount,
                               final int  feeRateMicros,
                               final int  amountPrecision) {
        // fee = amount * feeRateMicros / 1_000_000
        return (amount * feeRateMicros) / 1_000_000L;
    }

    /**
     * 对价格进行 tick 对齐（价格必须是 priceTick 的整数倍）。
     *
     * @param price     原始价格
     * @param priceTick 最小价格变动单位
     * @return 对齐后的价格（向下取整）
     */
    public static long alignToTick(final long price, final long priceTick) {
        return (price / priceTick) * priceTick;
    }

    /**
     * 检查价格是否是 tick 的整数倍。
     */
    public static boolean isValidTick(final long price, final long priceTick) {
        return price % priceTick == 0;
    }
}
