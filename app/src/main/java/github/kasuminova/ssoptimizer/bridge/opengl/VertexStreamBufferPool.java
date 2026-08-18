package github.kasuminova.ssoptimizer.bridge.opengl;

import org.jctools.queues.MpmcUnboundedXaddArrayQueue;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 顶点流编码缓冲池：跨线程复用 immediate 顶点流（{@link VertexStream}）的
 * 字节缓冲，替代「每次落帧 System.arraycopy 拷贝进批次命令」的路径。
 * <p>
 * 动机：v36 profile 显示 {@code VertexBatchCommand.fillFrom}（批次拷贝）
 * 与 {@code Arrays.copyOf}（缓冲扩容）占主线程录制耗时合计 3,700+ 样本；
 * v45c profile 显示 {@code VertexStreamBufferPool.acquire} 1,828 样本
 * （借出路径的多档位轮询）。
 * 本池配合 {@link VertexStream#transferBuffer()} 把缓冲所有权直接移交给
 * 批次命令（零拷贝），渲染线程执行完后按容量归还本池——容量跨帧保留，
 * 稳态下借出的缓冲大于等于近期批次峰值（{@link VertexStream} 预热），
 * 编码过程不再扩容。
 * <p>
 * <b>借出降频（A2）</b>：每个生产者线程带一个本地预借栈。{@link #acquire(int)}
 * 优先在本地栈中按容量搜索（数组操作，零 CAS 零队列访问）；未命中时从全局
 * 池批量补货（按需求档向下找，恰合当前 minCapacity 的缓冲直接返回、容量
 * 不足的入本地栈供更小需求、栈满放回对应档），补货仍空才新建档位容量缓冲。
 * 借出的缓冲最终经命令消费后由渲染线程 {@link #release(byte[])} 归还全局池，
 * 本地栈是「在途预借」，不会积压不回。
 * <p>
 * 分级策略：容量 {@value #MIN_CAPACITY}B ~ {@value #MAX_CAPACITY}B 按 2 的幂
 * 分桶，全部为空则新建；超过上限的请求直接分配非池化缓冲（归还时丢弃）。
 * 桶用 JCTools MPMC 无界 Xadd 数组队列：借出（主线程/aux 生产者，多消费者）
 * 与归还（渲染线程，单生产者）互不阻塞，且相对
 * {@link java.util.concurrent.ConcurrentLinkedDeque} 消除了链表节点 CAS
 * （v36 pollFirst 热点）；无界保证归还永不丢弃（固定容量队列会在归还峰值期
 * 出现「归还即丢弃」，使池化退化回每帧分配，v44 实测回归）。
 */
final class VertexStreamBufferPool {
    /** 最小桶容量（字节）。 */
    static final int MIN_CAPACITY = 512;
    /** 最大池化容量（字节）；超过即走非池化分配。 */
    static final int MAX_CAPACITY = 4 * 1024 * 1024;
    /** 每桶初始 chunk 容量（2 的幂）；全局队列按 chunk 链增长。 */
    private static final int PER_BUCKET_INITIAL_CAPACITY = 256;
    /** 每线程本地预借栈容量与单次补货预算。 */
    private static final int LOCAL_BATCH = 32;

    private static final int MIN_SHIFT = Integer.numberOfTrailingZeros(MIN_CAPACITY);
    private static final int BUCKET_COUNT = Integer.numberOfTrailingZeros(MAX_CAPACITY) - MIN_SHIFT + 1;

    @SuppressWarnings("unchecked")
    private final MpmcUnboundedXaddArrayQueue<byte[]>[] buckets = new MpmcUnboundedXaddArrayQueue[BUCKET_COUNT];
    private final ThreadLocal<LocalBufferStack> local =
            ThreadLocal.withInitial(() -> new LocalBufferStack(LOCAL_BATCH));
    /** 累计新建缓冲数（诊断/测试用：验证池化覆盖借出，见 {@link #totalAllocations()}）。 */
    private final AtomicInteger allocations = new AtomicInteger();

    VertexStreamBufferPool() {
        for (int i = 0; i < BUCKET_COUNT; i++) {
            buckets[i] = new MpmcUnboundedXaddArrayQueue<>(PER_BUCKET_INITIAL_CAPACITY);
        }
    }

    /**
     * 借出容量不小于 {@code minCapacity} 的缓冲（调用方随后独占写入，归还前
     * 不得再被借用）。优先命中本地预借栈（O(1) 数量级的数组搜索，零 CAS）；
     * 未命中时从需求档向下批量补货：任何归还缓冲都能被复用（旧实现从需求档
     * 向上找，低档归还缓冲永远不命中大需求，造成每帧新建大块且被池持有，
     * v44b 实测内存涨至 7.9GB OOM），恰合需求或更大档位的缓冲返回、容量不足
     * 的入栈供更小需求；补货仍空则新建档位容量缓冲（稳态不触发——预热
     * {@link VertexStream#transferBuffer()} 保证需求与近期批次峰值同量级，
     * 命中即零扩容零分配）。
     *
     * @param minCapacity 需要的最小容量（字节）
     * @return 容量 >= minCapacity 的缓冲
     */
    byte[] acquire(int minCapacity) {
        LocalBufferStack stack = local.get();
        byte[] hit = stack.findAndRemove(minCapacity);
        if (hit != null) {
            return hit;
        }
        // 批量补货：从需求档向下 poll（预算 LOCAL_BATCH），全部入本地栈
        // （容量不匹配的留栈供更小需求），随后从栈中取合适的——本地栈由此
        // 积累「在途预借」，后续 acquire 命中零队列访问
        int budget = LOCAL_BATCH;
        int start = Math.min(bucketIndexFor(minCapacity), BUCKET_COUNT - 1);
        for (int i = start; i >= 0 && budget > 0; i--) {
            while (budget > 0) {
                byte[] buffer = buckets[i].poll();
                if (buffer == null) {
                    break;
                }
                budget--;
                if (!stack.push(buffer)) {
                    // 栈满：放回对应档（无界队列，不丢对象）
                    buckets[bucketIndexFor(buffer.length)].offer(buffer);
                    break;
                }
            }
        }
        byte[] fromStack = stack.findAndRemove(minCapacity);
        if (fromStack != null) {
            return fromStack;
        }
        allocations.incrementAndGet();
        return new byte[capacityFor(minCapacity)];
    }

    /** 测试用：累计新建缓冲数（池化生效的验证指标）。 */
    int totalAllocations() {
        return allocations.get();
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

    /** 测试用：全局池内空闲缓冲总数（不含线程本地预借栈）。 */
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

    /** 每线程本地预借栈（字节缓冲专用）：数组 + 计数，仅本线程读写，无同步。 */
    private static final class LocalBufferStack {
        private final byte[][] items;
        private int size;

        LocalBufferStack(int capacity) {
            this.items = new byte[capacity][];
        }

        /** 从栈中取出容量不小于 {@code minCapacity} 的一个缓冲（从栈顶向下搜）。 */
        byte[] findAndRemove(int minCapacity) {
            for (int i = size - 1; i >= 0; i--) {
                byte[] buffer = items[i];
                if (buffer.length >= minCapacity) {
                    items[i] = items[size - 1];
                    items[size - 1] = null;
                    size--;
                    return buffer;
                }
            }
            return null;
        }

        boolean push(byte[] buffer) {
            if (size == items.length) {
                return false;
            }
            items[size++] = buffer;
            return true;
        }
    }
}
