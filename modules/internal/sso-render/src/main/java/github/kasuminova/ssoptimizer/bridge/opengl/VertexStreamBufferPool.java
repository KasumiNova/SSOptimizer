package github.kasuminova.ssoptimizer.bridge.opengl;

import org.apache.log4j.Logger;
import org.jctools.queues.MpmcUnboundedXaddArrayQueue;
import org.jctools.queues.SpscArrayQueue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
 * （v36 pollFirst 热点）。
 * <p>
 * <b>SPSC 返还通道（A6）</b>：v52 profile 显示 {@link #acquire(int)} 自身时间
 * 13.5s——全局 MPMC 队列的 poll/offer 跨核竞争与 per-buffer 原子计数是主因。
 * 每个生产者线程（{@code RecordingContext}）持有有界 SPSC 返还 inbox：
 * 渲染线程执行完顶点批次后把缓冲直接 offer 回<b>来源线程</b>的 inbox
 * （渲染线程是每个 inbox 的唯一生产者、来源线程是唯一消费者，无 CAS 只有
 * 有序写）；{@link #acquire(int, SpscArrayQueue)} 优先 drain 本线程 inbox
 * （零竞争），inbox 溢出才落全局池的既有归还路径。inbox 中的缓冲视同
 * 「在途借出」（离开全局池时已计入 inFlight，溢出落池时才走归还递减），
 * 保留上限启发式不受影响。全局补货的借出计数按桶聚合为单次
 * {@code addAndGet}（原 per-buffer 32×2 次原子 → 每补货 1~2 次）。
 * <p>
 * <b>有界保留（A5）</b>：v46 池降频改造后全局桶无界——任何归还的缓冲都会
 * 永久滞留（为避免 v44 固定容量池「归还即丢弃」的日志洪泛而走向另一极端），
 * 峰值帧借出的大容量缓冲（2MB~4MB 档）全部保留，JProfiler 实机 dump 显示
 * 池内 byte[] 累计 6,335 MB（约 80% 堆）。修复：按桶以「历史并发借出峰值 +
 * 松弛量」为保留上限，归还超限即丢弃；峰值按时间衰减（需求回落后主动排空
 * 超限库存），有界且不抖动——借出峰值在 acquire 侧即时抬升，稳态需求下
 * 池保留量收敛于需求水平，不会像固定上限那样在借出峰值期反复丢荒重建。
 * 硬约束沿用 v44 教训：{@link #acquire(int)} 仍从需求档向下补货；归还路径
 * 常态零日志，丢荒仅低频（约每分钟）汇总一次。
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
    /** 每线程 SPSC 返还 inbox 容量（渲染线程→来源生产者线程，见类 javadoc A6 节）。 */
    static final int RETURN_INBOX_CAPACITY = LOCAL_BATCH;
    /**
     * 每桶保留上限相对借出峰值（{@link #peakInFlight}）的松弛量：覆盖本地栈
     * 补货突发（一次 refill = {@link #LOCAL_BATCH}）与多线程同时 refill 的
     * 短时超出，避免峰值上升前的瞬时丢荒。
     */
    static final int RETENTION_SLACK = LOCAL_BATCH * 2;
    /** 借出峰值衰减间隔（纳秒）：超过该间隔未达峰时按 {@link #PEAK_DECAY_FACTOR} 收缩。 */
    private static final long PEAK_DECAY_INTERVAL_NANOS = 2_000_000_000L;
    /** 峰值衰减系数（每间隔收缩至 max(当前借出, 峰值 * 系数)）。 */
    private static final int PEAK_DECAY_FACTOR_NUM = 3;
    private static final int PEAK_DECAY_FACTOR_DEN = 4;
    /** 丢荒汇总日志间隔（纳秒）：约每分钟一次。 */
    private static final long DROP_LOG_INTERVAL_NANOS = 60_000_000_000L;
    /** 归还路径维护（衰减/排空/日志）的采样掩码：每 64 次归还执行一次。 */
    private static final int MAINTENANCE_MASK = 0x3F;

    private static final Logger LOGGER = Logger.getLogger(VertexStreamBufferPool.class);

    private static final int MIN_SHIFT = Integer.numberOfTrailingZeros(MIN_CAPACITY);
    private static final int BUCKET_COUNT = Integer.numberOfTrailingZeros(MAX_CAPACITY) - MIN_SHIFT + 1;

    @SuppressWarnings("unchecked")
    private final MpmcUnboundedXaddArrayQueue<byte[]>[] buckets = new MpmcUnboundedXaddArrayQueue[BUCKET_COUNT];
    private final ThreadLocal<LocalBufferStack> local =
            ThreadLocal.withInitial(() -> new LocalBufferStack(LOCAL_BATCH));
    /** 累计新建缓冲数（诊断/测试用：验证池化覆盖借出，见 {@link #totalAllocations()}）。 */
    private final AtomicInteger allocations = new AtomicInteger();
    /** 每桶当前借出未归（经 {@link #acquire(int)} 交出、尚未 {@link #release(byte[])} 归还）的缓冲数。 */
    private final AtomicInteger[] inFlight = new AtomicInteger[BUCKET_COUNT];
    /** 每桶借出高水位（带时间衰减）：保留上限 = 高水位 + {@link #RETENTION_SLACK}。 */
    private final AtomicInteger[] peakInFlight = new AtomicInteger[BUCKET_COUNT];
    private final AtomicLong[] lastDecayNanos = new AtomicLong[BUCKET_COUNT];
    /** 累计丢弃缓冲数（超限归还 + 峰值衰减后的主动排空）。 */
    private final AtomicLong droppedBuffers = new AtomicLong();
    private final AtomicLong droppedAtLastLog = new AtomicLong();
    private volatile long lastDropLogNanos;
    private final AtomicInteger releaseCounter = new AtomicInteger();

    VertexStreamBufferPool() {
        for (int i = 0; i < BUCKET_COUNT; i++) {
            buckets[i] = new MpmcUnboundedXaddArrayQueue<>(PER_BUCKET_INITIAL_CAPACITY);
            inFlight[i] = new AtomicInteger();
            peakInFlight[i] = new AtomicInteger();
            lastDecayNanos[i] = new AtomicLong();
        }
    }

    /**
     * 借出容量不小于 {@code minCapacity} 的缓冲（调用方随后独占写入，归还前
     * 不得再被借用）。无 SPSC 返还 inbox 的调用形态（测试与无上下文路径），
     * 等价于 {@link #acquire(int, SpscArrayQueue)} 的 inbox=null。
     */
    byte[] acquire(int minCapacity) {
        return acquire(minCapacity, null);
    }

    /**
     * 借出容量不小于 {@code minCapacity} 的缓冲。查找顺序：本地预借栈
     * （数组操作，零 CAS）→ 本线程 SPSC 返还 inbox（渲染线程直还，
     * 单生产者单消费者，零竞争 drain 进本地栈）→ 全局池按需求档向下
     * 批量补货（任何归还缓冲都能被复用——旧实现从需求档向上找，低档归还
     * 缓冲永远不命中大需求，造成每帧新建大块且被池持有，v44b 实测内存涨至
     * 7.9GB OOM）→ 新建档位容量缓冲（稳态不触发——预热
     * {@link VertexStream#transferBuffer()} 保证需求与近期批次峰值同量级，
     * 命中即零扩容零分配）。
     *
     * @param minCapacity 需要的最小容量（字节）
     * @param inbox       本线程的 SPSC 返还 inbox（渲染线程归还的缓冲），可为 null
     * @return 容量 >= minCapacity 的缓冲
     */
    byte[] acquire(int minCapacity, SpscArrayQueue<byte[]> inbox) {
        LocalBufferStack stack = local.get();
        byte[] hit = stack.findAndRemove(minCapacity);
        if (hit != null) {
            // 栈命中零原子操作：借出计数在缓冲离开全局池（补货/新建）时即已记录，
            // 本地栈持有期间视同「在途」（它们确实不在池内）
            return hit;
        }
        if (inbox != null) {
            // SPSC 返还通道 drain：inbox 中的缓冲离开全局池时已计入 inFlight，
            // 迁入本地栈不做账；本地栈满溢出的落全局归还路径（计数平衡）
            byte[] returned;
            while ((returned = inbox.poll()) != null) {
                if (!stack.push(returned)) {
                    release(returned);
                }
            }
            hit = stack.findAndRemove(minCapacity);
            if (hit != null) {
                return hit;
            }
        }
        // 批量补货：从需求档向下 poll（预算 LOCAL_BATCH），全部入本地栈
        // （容量不匹配的留栈供更小需求），随后从栈中取合适的——本地栈由此
        // 积累「在途预借」，后续 acquire 命中零队列访问；借出计数按桶聚合
        int budget = LOCAL_BATCH;
        int start = Math.min(bucketIndexFor(minCapacity), BUCKET_COUNT - 1);
        for (int i = start; i >= 0 && budget > 0; i--) {
            int borrowed = 0;
            while (budget > 0) {
                byte[] buffer = buckets[i].poll();
                if (buffer == null) {
                    break;
                }
                budget--;
                if (!stack.push(buffer)) {
                    // 栈满：放回本档（无界队列，不丢对象），不计借出账
                    buckets[i].offer(buffer);
                    break;
                }
                borrowed++;
            }
            if (borrowed > 0) {
                trackBorrowBatch(i, borrowed);
            }
        }
        byte[] fromStack = stack.findAndRemove(minCapacity);
        if (fromStack != null) {
            return fromStack;
        }
        allocations.incrementAndGet();
        byte[] created = new byte[capacityFor(minCapacity)];
        trackBorrow(created);
        return created;
    }

    /**
     * 借出计数：缓冲离开全局池时记录到所在桶的借出高水位（保留上限 = 高水位 +
     * {@link #RETENTION_SLACK}）。超上限的非池化缓冲不参与——它永不入池，
     * {@link #release(byte[])} 对其早退，计数必须保持平衡。
     */
    private void trackBorrow(byte[] buffer) {
        final int capacity = buffer.length;
        if (capacity >= MIN_CAPACITY && capacity <= MAX_CAPACITY) {
            final int bucket = bucketIndexFor(capacity);
            final int cur = inFlight[bucket].incrementAndGet();
            // 峰值只是保留策略的启发式高水位，并发下允许丢失更新（瞬时偏低至多
            // 让归还侧多丢一个缓冲，下次借出即重新抬升）；相对 updateAndGet 的
            // CAS 重试循环，省去录制热路径上与归还线程的跨核重试。
            final AtomicInteger peak = peakInFlight[bucket];
            if (cur > peak.get()) {
                peak.set(cur);
            }
        }
    }

    /**
     * 按桶批量借出计数（全局补货路径）：单次 {@code addAndGet} 替代原
     * per-buffer 的 {@link #trackBorrow(byte[])} 循环（补 32 个 = 64+ 次原子
     * 操作 → 1~2 次）。峰值语义同 {@link #trackBorrow(byte[])}（启发式高水位，
     * 并发下允许丢失更新）。
     */
    private void trackBorrowBatch(int bucket, int count) {
        final int cur = inFlight[bucket].addAndGet(count);
        final AtomicInteger peak = peakInFlight[bucket];
        if (cur > peak.get()) {
            peak.set(cur);
        }
    }

    /** 测试用：累计新建缓冲数（池化生效的验证指标）。 */
    int totalAllocations() {
        return allocations.get();
    }

    /**
     * 归还缓冲（渲染线程执行完顶点批次命令后）。容量落在池化区间内则按容量
     * 进位入档；保留量超过该档上限（借出峰值 + 松弛量）的缓冲直接丢弃——
     * 峰值由 {@link #acquire(int)} 侧即时抬升、按时间衰减回落，稳态下池保留
     * 量收敛于实际并发借出水平（JProfiler 实测修复前无界保留 6,335 MB）。
     * 归还路径常态零日志；丢荒仅在约每分钟一次的维护点上汇总输出。
     */
    void release(byte[] buffer) {
        int capacity = buffer.length;
        if (capacity < MIN_CAPACITY || capacity > MAX_CAPACITY) {
            // 非池化缓冲（低于最小档或超过上限的大块分配）：直接丢弃
            return;
        }
        int bucket = bucketIndexFor(capacity);
        inFlight[bucket].decrementAndGet();
        if ((releaseCounter.incrementAndGet() & MAINTENANCE_MASK) == 0) {
            final long now = System.nanoTime();
            maintainBucket(bucket, now);
            maybeLogDrops(now);
        }
        // 保留上限检查保持逐次精确（size() 为 O(1) 两次 volatile 读，
        // 采样化会打破「超限归还即丢」语义，收益不抵语义损失）
        if (buckets[bucket].size() < peakInFlight[bucket].get() + RETENTION_SLACK) {
            buckets[bucket].offer(buffer);
        } else {
            droppedBuffers.incrementAndGet();
        }
    }

    /**
     * 单桶维护（低频调用）：峰值时间衰减 + 主动排空超限库存。
     * <p>
     * 峰值衰减把「历史借出高水位」收缩向当前需求——若需求长期未达峰，峰值每
     * {@link #PEAK_DECAY_INTERVAL_NANOS} 收缩 {@link #PEAK_DECAY_FACTOR_NUM}/
     * {@link #PEAK_DECAY_FACTOR_DEN}，使保留上限跟随需求回落；排空直接摘除
     * 超限库存（从桶尾 poll 丢弃），否则仅靠「归还超限丢弃」无法消化峰值期
     * 滞留的存量。借出峰值在 acquire 侧即时抬升，因此维护不会在稳态需求下
     * 误伤库存（上限恒 ≥ 当前需求 + 松弛量）。
     */
    private void maintainBucket(int bucket, long now) {
        if (now - lastDecayNanos[bucket].get() >= PEAK_DECAY_INTERVAL_NANOS) {
            lastDecayNanos[bucket].set(now);
            final int cur = inFlight[bucket].get();
            final int peak = peakInFlight[bucket].get();
            peakInFlight[bucket].set(Math.max(cur, peak * PEAK_DECAY_FACTOR_NUM / PEAK_DECAY_FACTOR_DEN));
        }
        final int limit = peakInFlight[bucket].get() + RETENTION_SLACK;
        while (buckets[bucket].size() > limit) {
            if (buckets[bucket].poll() == null) {
                break;
            }
            droppedBuffers.incrementAndGet();
        }
    }

    /** 丢荒汇总日志：约每分钟一次，仅在有丢弃时输出（常态零日志）。 */
    private void maybeLogDrops(long now) {
        if (now - lastDropLogNanos < DROP_LOG_INTERVAL_NANOS) {
            return;
        }
        lastDropLogNanos = now;
        final long total = droppedBuffers.get();
        final long sinceLast = total - droppedAtLastLog.getAndSet(total);
        if (sinceLast > 0) {
            LOGGER.warn("[SSOptimizer] VertexStreamBufferPool 丢弃 " + sinceLast
                    + " 个缓冲（保留上限收紧，累计 " + total + " 个）");
        }
    }

    /** 测试用：对所有桶强制执行一次峰值衰减 + 超限排空（模拟维护点时间流逝）。 */
    void decayPeaksForTest() {
        final long now = System.nanoTime();
        for (int i = 0; i < BUCKET_COUNT; i++) {
            lastDecayNanos[i].set(now - PEAK_DECAY_INTERVAL_NANOS - 1L);
            maintainBucket(i, now);
        }
    }

    /** 测试用：累计丢弃缓冲数（超限归还 + 主动排空）。 */
    long droppedBufferCount() {
        return droppedBuffers.get();
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
