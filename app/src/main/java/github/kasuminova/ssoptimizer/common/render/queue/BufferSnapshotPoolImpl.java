package github.kasuminova.ssoptimizer.common.render.queue;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link BufferSnapshotPool} 的默认实现：2 的幂分级直接缓冲池。
 * <p>
 * 分级策略：容量 {@value #MIN_CAPACITY}B ~ {@value #MAX_CAPACITY}B 按 2 的幂分桶，
 * 借出时向上取整到最近的桶容量；超过上限的请求直接分配非池化缓冲（释放时不入池）。
 * 桶用无锁队列实现，录制线程（主线程/aux 生产者）借出与渲染线程归还互不阻塞。
 */
public final class BufferSnapshotPoolImpl implements BufferSnapshotPool {
    /** 最小桶容量（字节）。 */
    public static final int MIN_CAPACITY = 256;
    /** 最大池化容量（字节）；超过即走非池化分配。 */
    public static final int MAX_CAPACITY = 16 * 1024 * 1024;

    private static final int MIN_SHIFT = Integer.numberOfTrailingZeros(MIN_CAPACITY);
    private static final int BUCKET_COUNT = Integer.numberOfTrailingZeros(MAX_CAPACITY) - MIN_SHIFT + 1;

    private final ConcurrentLinkedDeque<ByteBuffer>[] buckets;
    private final AtomicInteger allocations = new AtomicInteger();

    @SuppressWarnings("unchecked")
    public BufferSnapshotPoolImpl() {
        this.buckets = new ConcurrentLinkedDeque[BUCKET_COUNT];
        for (int i = 0; i < BUCKET_COUNT; i++) {
            buckets[i] = new ConcurrentLinkedDeque<>();
        }
    }

    @Override
    public ByteBuffer borrow(int bytes) {
        if (bytes < 1) {
            bytes = 1;
        }
        if (bytes > MAX_CAPACITY) {
            allocations.incrementAndGet();
            return ByteBuffer.allocateDirect(bytes);
        }
        int bucketIndex = bucketIndexFor(bytes);
        ByteBuffer buffer = buckets[bucketIndex].poll();
        if (buffer == null) {
            allocations.incrementAndGet();
            buffer = ByteBuffer.allocateDirect(MIN_CAPACITY << bucketIndex);
        }
        buffer.clear();
        return buffer;
    }

    @Override
    public void release(ByteBuffer buffer) {
        int capacity = buffer.capacity();
        if (capacity < MIN_CAPACITY || capacity > MAX_CAPACITY || (capacity & (capacity - 1)) != 0) {
            // 非池化缓冲（超过上限的大块分配）：直接丢弃
            return;
        }
        buckets[bucketIndexFor(capacity)].offer(buffer);
    }

    @Override
    public ByteBuffer snapshot(ByteBuffer src) {
        int bytes = src.remaining();
        ByteBuffer dst = borrow(bytes);
        dst.order(src.order());
        dst.put(src.duplicate());
        dst.flip();
        return dst;
    }

    @Override
    public ByteBuffer snapshot(DoubleBuffer src) {
        int bytes = src.remaining() * Double.BYTES;
        ByteBuffer dst = borrow(bytes);
        dst.order(src.order());
        dst.asDoubleBuffer().put(src.duplicate());
        dst.limit(bytes);
        return dst;
    }

    @Override
    public ByteBuffer snapshot(FloatBuffer src) {
        int bytes = src.remaining() * Float.BYTES;
        ByteBuffer dst = borrow(bytes);
        dst.order(src.order());
        dst.asFloatBuffer().put(src.duplicate());
        dst.limit(bytes);
        return dst;
    }

    @Override
    public ByteBuffer snapshot(IntBuffer src) {
        int bytes = src.remaining() * Integer.BYTES;
        ByteBuffer dst = borrow(bytes);
        dst.order(src.order());
        dst.asIntBuffer().put(src.duplicate());
        dst.limit(bytes);
        return dst;
    }

    @Override
    public ByteBuffer snapshot(ShortBuffer src) {
        int bytes = src.remaining() * Short.BYTES;
        ByteBuffer dst = borrow(bytes);
        dst.order(src.order());
        dst.asShortBuffer().put(src.duplicate());
        dst.limit(bytes);
        return dst;
    }

    @Override
    public int pooledBufferCount() {
        int total = 0;
        for (ConcurrentLinkedDeque<ByteBuffer> bucket : buckets) {
            total += bucket.size();
        }
        return total;
    }

    @Override
    public int totalAllocations() {
        return allocations.get();
    }

    /** 字节数向上取整到桶下标（不小于 MIN_CAPACITY 的 2 的幂）。 */
    private static int bucketIndexFor(int bytes) {
        int shift = Integer.SIZE - Integer.numberOfLeadingZeros(bytes - 1);
        return Math.max(shift - MIN_SHIFT, 0);
    }
}
