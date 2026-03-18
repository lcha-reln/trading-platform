package com.trading.matching.orderbook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OrderNode 专用预分配对象池。
 *
 * <p>设计约束：
 * <ul>
 *   <li>非线程安全 —— 撮合引擎单线程调用，无需同步。</li>
 *   <li>容量固定 —— 启动时根据预估最大在途订单数配置；
 *       建议初始值 = 单交易对最大挂单数 × 1.5。</li>
 *   <li>池空时返回 {@code null}，调用方须处理（记录告警、触发熔断）。</li>
 * </ul>
 *
 * @author Reln Ding
 */
public class OrderNodePool {
    /**
     * 默认容量：支持 100 万在途订单
     */
    public static final int DEFAULT_CAPACITY = 1_000_000;
    private static final Logger log = LoggerFactory.getLogger(OrderNodePool.class);

    private final OrderNode[] pool;
    private int top;   // 栈顶指针，指向下一个可借出的槽位

    public OrderNodePool(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got: " + capacity);
        }

        this.pool = new OrderNode[capacity];
        this.top = capacity;

        for (int i = 0; i < capacity; i++) {
            pool[i] = new OrderNode();
        }
    }

    /**
     * 从池中借出一个 OrderNode。
     *
     * @return 可用的 OrderNode，或 {@code null}（池空时）
     */
    public OrderNode borrow() {
        if (top == 0) {
            return null;   // 池已耗尽，调用方需处理
        }

        return pool[--top]; // 先把指针移到有效位置，再取值
    }

    /**
     * 将 OrderNode 归还池中。
     * 调用方须在归还前调用 {@link OrderNode#reset()} 清空字段。
     *
     * @param node 待归还节点（不得为 null）
     */
    public void release(final OrderNode node) {
        if (top < pool.length) {
            pool[top++] = node;
        }

        log.error("超出容量说明有重复归还 BUG，静默丢弃并可在此处加监控");
    }

    /**
     * 当前可借出数量
     */
    public int available() {
        return top;
    }

    /**
     * 池总容量
     */
    public int capacity() {
        return pool.length;
    }

    /**
     * 是否已空
     */
    public boolean isEmpty() {
        return top == 0;
    }
}
