package com.trading.matching.orderbook;

/**
 * 价格档位 —— 同一价格下所有挂单的集合。
 *
 * <p>内部结构：双向链表（head → ... → tail），按时间先进先出排列。
 * <ul>
 *   <li>新订单追加到 tail（时间最晚）。</li>
 *   <li>撮合时从 head 开始消费（时间最早，优先成交）。</li>
 *   <li>撤单时通过 {@link OrderNode#prev}/{@link OrderNode#next} 直接 O(1) 摘除。</li>
 * </ul>
 *
 * @author Reln Ding
 */
public class PriceLevel {
    /**
     * 价格（固定精度整数，与 OrderNode.price 一致）
     */
    public final long price;

    /**
     * 链表头节点（时间最早，优先撮合）
     */
    public OrderNode head;

    /**
     * 链表尾节点（时间最晚，新单追加处）
     */
    public OrderNode tail;

    /**
     * 该档位所有挂单的剩余数量之和（leavesQty 之和）
     */
    public long totalQty;

    /**
     * 该档位挂单数量
     */
    public int orderCount;

    public PriceLevel(final long price) {
        this.price = price;
        this.head = null;
        this.tail = null;
        this.totalQty = 0L;
        this.orderCount = 0;
    }

    /**
     * 将订单追加到链表尾部（新单入簿）。
     *
     * <p>流程：
     * <pre>
     *  addOrder(node)
     *        │
     *        ▼
     *  ┌─────────────────────┐
     *  │ node.prev = tail    │  ← node 的前驱指向当前尾节点
     *  │ node.next = null    │  ← node 是新的末尾，无后继
     *  └─────────┬───────────┘
     *            │
     *            ▼
     *       tail == null ?
     *      （链表是否为空）
     *         │         │
     *        YES        NO
     *         │         │
     *         ▼         ▼
     *    head = node  tail.next = node   ← 原尾节点的 next 指向新节点
     *         │         │
     *         └────┬────┘
     *              │
     *              ▼
     *         tail = node        ← 更新尾指针
     *              │
     *              ▼
     *    totalQty += node.leavesQty
     *              │
     *              ▼
     *         orderCount++
     * </pre>
     *
     * <p>链表变化示意：
     * <pre>
     *  情况 A —— 链表为空（tail == null）：
     *    插入前：head = null, tail = null
     *    插入后：head → [node] ← tail
     *
     *  情况 B —— 链表已有节点：
     *    插入前：head → [A] ↔ [B] ← tail
     *    插入后：head → [A] ↔ [B] ↔ [node] ← tail
     * </pre>
     *
     * @param node 已初始化的 OrderNode
     */
    public void addOrder(final OrderNode node) {
        node.prev = tail;
        node.next = null;

        if (tail != null) {
            tail.next = node;
        } else {
            head = node;
        }

        tail = node;
        totalQty += node.leavesQty;
        orderCount++;
    }

    /**
     * 从链表中摘除指定节点（撤单或全成交后调用）。
     *
     * @param node 要摘除的节点
     */
    public void removeOrder(final OrderNode node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }

        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }

        node.prev = null;
        node.next = null;
        totalQty -= node.leavesQty;
        orderCount--;
    }

    /**
     * 成交后减少档位总量（部分成交时调用，不移除节点）。
     *
     * @param filledQty 本次成交数量
     */
    public void reduceQty(final long filledQty) {
        totalQty -= filledQty;
    }

    /**
     * 档位是否为空（无挂单）
     */
    public boolean isEmpty() {
        return orderCount == 0;
    }

    @Override
    public String toString() {
        return "PriceLevel{price=" + price
                + ", totalQty=" + totalQty
                + ", orderCount=" + orderCount
                + '}';
    }
}
