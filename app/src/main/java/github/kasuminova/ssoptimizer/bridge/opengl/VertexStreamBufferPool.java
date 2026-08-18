package github.kasuminova.ssoptimizer.bridge.opengl;

import org.jctools.queues.MpmcUnboundedXaddArrayQueue;

/**
 * 顶点流编码缓冲池：跨线程复用 immediate 顶点流（{@link VertexStream}）的
 * 字节缓冲，替代「每次落帧 System.arraycopy 拷贝进批次命令」的路径。
 * <p>
 * 动机：v36 profile 显示 {@code VertexBatchCommand.fillFrom}（批次拷贝）
 * 与 {@code Arrays.copyOf}（缓冲扩容）占主线程录制耗时合计 3,700+ 样本。
 * 本池配合 {@link VertexStream#transferBuffer()} 把缓冲所有权直接移交给
 * 批次命令（零拷贝），渲染线程执行完后按容量归还本池——容量跨帧保留，
 * 稳态下每次借出的缓冲都大于等于历史峰值，编码过程不再扩容。
 * <p>
 * 分级策略：容量 {@value #MIN_CAPACITY}B ~ {@value #MAX_CAPACITY}B 按 2 的幂
 * 分桶，借出时从「大于等于需求的最小档」向后找非空档，全部为空则新建；
 * 超过上限的请求直接分配非池化缓冲（归还时丢弃）。桶用 JCTools MPMC 无界
 * Xadd 数组队列：借出（主线程/aux 生产者，多消费者）与归还（渲染线程，
 * 单生产者）互不阻塞，且相对 {@link java.util.concurrent.ConcurrentLinkedDeque}
 * 消除了链表节点 CAS（v36 pollFirst 热点）；无界保证归还永不丢弃（固定容量
 * 队列会在归还峰值期「归还即丢弃」，使池化退化回每帧分配，v44 实测回归）。
 */
final class VertexStreamBufferPool {
    /** 最小桶容量（字节）。 */
    static final int MIN_CAPACITY = 512;
    /** 最大池化容量（字节）；超过即走非池化分配。 */
    static final int MAX_CAPACITY = 4 * 1024 * 1024;
    /** 每桶初始 chunk 容量（2 的幂）；队列按 chunk 链增长。 */
    private static final int PER_BUCKET_INITIAL_CAPACITY = 256;

    private static final int MIN_SHIFT = Integer.numberOfTrailingZeros(MIN_CAPACITY);
    private static final int BUCKET_COUNT = Integer.numberOfTrailingZeros(MAX_CAPACITY) - MIN_SHIFT + 1;

    @SuppressWarnings("unchecked")
    private final MpmcUnboundedXaddArrayQueue<byte[]>[] buckets = new MpmcUnboundedXaddArrayQueue[BUCKET_COUNT];

    VertexStreamBufferPool() {
        for (int i = 0; i < BUCKET_COUNT; i++) {
            buckets[i] = new MpmcUnboundedXaddArrayQueue<>(PER_BUCKET_INITIAL_CAPACITY);
        }
    }

    /**
     * 借出容量不小于 {@code minCapacity} 的缓冲（调用方随后独占写入，归还前
     * 不得再被借用）。从需求档开始向下找最近的非空档取用——任何归还缓冲都能
     * 被复用（旧实现从需求档向上找，低档归还缓冲永远不命中大需求，造成每帧
     * 新建大块且被池持有，v44b 实测内存涨至 7.9GB OOM）；需求档在稳态恒有
     * 上帧归还原档的缓冲，命中即零扩容零分配。全部为空则新建档位容量缓冲
     * （容量不足由编码端 ensure 渐进扩容，归还按实际容量入档）。
     *
     * @param minCapacity 需要的最小容量（字节）
     * @return 容量 >= minCapacity 的缓冲
     */
    byte[] acquire(int minCapacity) {
        int start = Math.min(bucketIndexFor(minCapacity), BUCKET_COUNT - 1);
        for (int i = start; i >= 0; i--) {
            byte[] buffer = buckets[i].poll();
            if (buffer != null) {
                return buffer;
            }
        }
        return new byte[capacityFor(minCapacity)];
    }

    /**
     * 归还缓冲（渲染线程执行完顶点批次命令后）。容量落在池化区间内则按
     * 容量进位入档；无界队列，不丢对象。
     */
    void release(byte[] buffer) {
        int capacity = buffer.length;
        if (capacity < MIN_CAPACITY || capacity > MAX_CAPACITY) {
            // 非池化缓冲（低于最小档或超过上限的大块分配）：直接丢弃
            return;
        }
        buckets[bucketIndexFor(capacity)].offer(buffer);
    }

    /** 测试用：池内空闲缓冲总数。 */
    int pooledBufferCount() {
        int total = 0;
        for (MpmcUnboundedXaddArrayQueue<byte[]> bucket : buckets) {
            total += bucket.size();
        }
        return total;
    }

    /** 向上取整到不小于 {@code bytes} 的 2 的幂（且不小于最小档容量）。 */
    private static int capacityFor(int bytes) {
        if (bytes <= MIN_CAPACITY) {
            return MIN_CAPACITY;
        }
        int shift = Integer.SIZE - Integer.numberOfLeadingZeros(bytes - 1);
        return 1 << shift;
    }

    /** {@link #capacityFor(int)} 结果对应的桶下标。 */
    private static int bucketIndexFor(int bytes) {
        return Integer.numberOfTrailingZeros(capacityFor(bytes)) - MIN_SHIFT;
    }
}
