package com.trading.util;

import java.util.function.Supplier;

/**
 * 轻量级单线程对象池。
 * <p>
 * 设计目标：
 *   - 消除热路径对象分配，实现零 GC
 *   - 非线程安全，只允许单线程使用（撮合引擎是单线程的）
 *   - 底层使用 Object[] 栈，避免泛型装箱
 * <p>
 * 用法示例：
 *   // 初始化（系统启动时，预分配 1024 个 OrderNode）
 *   ObjectPool<OrderNode> pool = new ObjectPool<>(OrderNode::new, 1024);
 * <p>
 *   // 取出
 *   OrderNode node = pool.borrow();
 *   node.reset(orderId, price, quantity);
 * <p>
 *   // 归还（成交/撤销后）
 *   pool.release(node);
 */
public final class ObjectPool<T> {
    private final Object[] pool;
    private int top;    // 栈顶指针

    /**
     * @param factory  对象工厂（只在初始化时调用）
     * @param capacity 预分配容量
     */
    @SuppressWarnings("unchecked")
    public ObjectPool(final Supplier<T> factory, final int capacity) {
        this.pool = new Object[capacity];
        this.top = capacity;

        for (int i = 0; i < capacity; i++) {
            pool[i] = factory.get();
        }
    }

    /**
     * 从池中借出一个对象。
     * 如果池已空，返回 null（调用方需处理）。
     *
     * @return 对象实例，或 null（池空时）
     */
    @SuppressWarnings("unchecked")
    public T borrow() {
        if (top == 0) {
            return null;  // 池已空，需扩容或报警
        }
        return (T) pool[--top];
    }

    /**
     * 将对象归还到池中。
     * 调用方需在归还前重置对象状态，防止脏数据。
     *
     * @param obj 要归还的对象
     */
    public void release(final T obj) {
        if (top < pool.length) {
            pool[top++] = obj;
        }
        // 超出容量则丢弃（不应发生，可加监控）
    }

    /** 当前可用对象数量 */
    public int available() {
        return top;
    }

    /** 池总容量 */
    public int capacity() {
        return pool.length;
    }

    /** 是否为空 */
    public boolean isEmpty() {
        return top == 0;
    }
}
