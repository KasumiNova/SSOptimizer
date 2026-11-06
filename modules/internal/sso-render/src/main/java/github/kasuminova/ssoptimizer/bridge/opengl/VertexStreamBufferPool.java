package github.kasuminova.ssoptimizer.bridge.opengl;

import org.apache.log4j.Logger;
import org.jctools.queues.MpmcUnboundedXaddArrayQueue;
import org.jctools.queues.SpscArrayQueue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 跨线程复用顶点流缓冲；渲染线程优先经 SPSC inbox 归还来源线程，全局桶按衰减后的借出峰值限制保留量。 */
final class VertexStreamBufferPool {
    static final int MIN_CAPACITY = 512;
    static final int MAX_CAPACITY = 4 * 1024 * 1024;
    private static final int PER_BUCKET_INITIAL_CAPACITY = 256;
    private static final int LOCAL_BATCH = 32;
    static final int RETURN_INBOX_CAPACITY = LOCAL_BATCH;
    static final int RETENTION_SLACK = LOCAL_BATCH * 2;
    private static final long PEAK_DECAY_INTERVAL_NANOS = 2_000_000_000L;
    private static final int PEAK_DECAY_FACTOR_NUM = 3;
    private static final int PEAK_DECAY_FACTOR_DEN = 4;
    private static final long DROP_LOG_INTERVAL_NANOS = 60_000_000_000L;
    private static final int MAINTENANCE_MASK = 0x3F;

    private static final Logger LOGGER = Logger.getLogger(VertexStreamBufferPool.class);

    private static final int MIN_SHIFT = Integer.numberOfTrailingZeros(MIN_CAPACITY);
    private static final int BUCKET_COUNT = Integer.numberOfTrailingZeros(MAX_CAPACITY) - MIN_SHIFT + 1;

    @SuppressWarnings("unchecked")
    private final MpmcUnboundedXaddArrayQueue<byte[]>[] buckets = new MpmcUnboundedXaddArrayQueue[BUCKET_COUNT];
    private final ThreadLocal<LocalBufferStack> local =
            ThreadLocal.withInitial(() -> new LocalBufferStack(LOCAL_BATCH));
    private final AtomicInteger allocations = new AtomicInteger();
    private final AtomicInteger[] inFlight = new AtomicInteger[BUCKET_COUNT];
    private final AtomicInteger[] peakInFlight = new AtomicInteger[BUCKET_COUNT];
    private final AtomicLong[] lastDecayNanos = new AtomicLong[BUCKET_COUNT];
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

    byte[] acquire(int minCapacity) {
        return acquire(minCapacity, null);
    }

    /** 返回容量不小于 minCapacity 的独占缓冲，inbox 可为 null。 */
    byte[] acquire(int minCapacity, SpscArrayQueue<byte[]> inbox) {
        // 优先复用来源线程刚归还的缓冲，未命中再走本地栈和全局池。
        if (inbox != null) {
            byte[] head = inbox.poll();
            if (head != null) {
                if (head.length >= minCapacity) {
                            return head;
                }
                if (!local.get().push(head)) {
                    release(head);
                }
            }
        }
        LocalBufferStack stack = local.get();
        byte[] hit = stack.findAndRemove(minCapacity);
        if (hit != null) {
            // 本地栈中的缓冲仍计为在途，命中时不调整计数。
            return hit;
        }
        if (inbox != null) {
            // inbox 缓冲已计入在途，迁入本地栈不重复计数。
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
        // 全局补货按桶聚合借出计数，栈满时逐出最老缓冲，避免低档缓冲占满本地栈。
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
                    release(stack.evictBottom());
                    stack.push(buffer);
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

    private void trackBorrow(byte[] buffer) {
        final int capacity = buffer.length;
        if (capacity >= MIN_CAPACITY && capacity <= MAX_CAPACITY) {
            final int bucket = bucketIndexFor(capacity);
            final int cur = inFlight[bucket].incrementAndGet();
            // 峰值允许并发丢失更新，后续借出会重新抬升。
            final AtomicInteger peak = peakInFlight[bucket];
            if (cur > peak.get()) {
                peak.set(cur);
            }
        }
    }

    private void trackBorrowBatch(int bucket, int count) {
        final int cur = inFlight[bucket].addAndGet(count);
        final AtomicInteger peak = peakInFlight[bucket];
        if (cur > peak.get()) {
            peak.set(cur);
        }
    }

    int totalAllocations() {
        return allocations.get();
    }


    /** 只能归还借自本池的同一数组；超出保留上限的缓冲被丢弃。 */
    void release(byte[] buffer) {
        int capacity = buffer.length;
        if (capacity < MIN_CAPACITY || capacity > MAX_CAPACITY) {
            return;
        }
        int bucket = bucketIndexFor(capacity);
        inFlight[bucket].decrementAndGet();
        if ((releaseCounter.incrementAndGet() & MAINTENANCE_MASK) == 0) {
            final long now = System.nanoTime();
            maintainBucket(bucket, now);
            maybeLogDrops(now);
        }
        if (buckets[bucket].size() < peakInFlight[bucket].get() + RETENTION_SLACK) {
            buckets[bucket].offer(buffer);
        } else {
            droppedBuffers.incrementAndGet();
        }
    }

    /** 按时间衰减峰值并排空超出保留上限的库存。 */
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

    private void maybeLogDrops(long now) {
        if (now - lastDropLogNanos < DROP_LOG_INTERVAL_NANOS) {
            return;
        }
        lastDropLogNanos = now;
        final long total = droppedBuffers.get();
        final long sinceLast = total - droppedAtLastLog.getAndSet(total);
        if (sinceLast > 0) {
            LOGGER.warn("[SSOptimizer] VertexStreamBufferPool 丢弃 " + sinceLast
                    + " 个缓冲（保留上限收紧，累计 " + total + " 个，累计新建 "
                    + allocations.get() + " 个）");
        }
    }

    void decayPeaksForTest() {
        final long now = System.nanoTime();
        for (int i = 0; i < BUCKET_COUNT; i++) {
            lastDecayNanos[i].set(now - PEAK_DECAY_INTERVAL_NANOS - 1L);
            maintainBucket(i, now);
        }
    }

    long droppedBufferCount() {
        return droppedBuffers.get();
    }

    int pooledBufferCount() {
        int total = 0;
        for (MpmcUnboundedXaddArrayQueue<byte[]> bucket : buckets) {
            total += bucket.size();
        }
        return total;
    }

    private static int capacityFor(int bytes) {
        if (bytes <= MIN_CAPACITY) {
            return MIN_CAPACITY;
        }
        int shift = Integer.SIZE - Integer.numberOfLeadingZeros(bytes - 1);
        return 1 << shift;
    }

    private static int bucketIndexFor(int bytes) {
        return Integer.numberOfTrailingZeros(capacityFor(bytes)) - MIN_SHIFT;
    }

    private static final class LocalBufferStack {
        private final byte[][] items;
        private int size;

        LocalBufferStack(int capacity) {
            this.items = new byte[capacity][];
        }

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

        byte[] evictBottom() {
            byte[] bottom = items[0];
            System.arraycopy(items, 1, items, 0, size - 1);
            items[--size] = null;
            return bottom;
        }
    }
}
