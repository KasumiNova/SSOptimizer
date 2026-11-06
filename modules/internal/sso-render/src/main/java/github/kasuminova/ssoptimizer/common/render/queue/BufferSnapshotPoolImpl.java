package github.kasuminova.ssoptimizer.common.render.queue;

import org.jctools.queues.MpmcUnboundedXaddArrayQueue;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicInteger;

public final class BufferSnapshotPoolImpl implements BufferSnapshotPool {
    public static final int MIN_CAPACITY = 256;
    public static final int MAX_CAPACITY = 16 * 1024 * 1024;
    private static final int PER_BUCKET_INITIAL_CAPACITY = 128;

    private static final int MIN_SHIFT = Integer.numberOfTrailingZeros(MIN_CAPACITY);
    private static final int BUCKET_COUNT = Integer.numberOfTrailingZeros(MAX_CAPACITY) - MIN_SHIFT + 1;

    private final MpmcUnboundedXaddArrayQueue<ByteBuffer>[] buckets;
    private final AtomicInteger allocations = new AtomicInteger();

    @SuppressWarnings("unchecked")
    public BufferSnapshotPoolImpl() {
        this.buckets = new MpmcUnboundedXaddArrayQueue[BUCKET_COUNT];
        for (int i = 0; i < BUCKET_COUNT; i++) {
            buckets[i] = new MpmcUnboundedXaddArrayQueue<>(PER_BUCKET_INITIAL_CAPACITY);
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
            return;
        }
        int index = bucketIndexFor(capacity);
        buckets[index].offer(buffer);
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
        for (MpmcUnboundedXaddArrayQueue<ByteBuffer> bucket : buckets) {
            total += bucket.size();
        }
        return total;
    }

    @Override
    public int totalAllocations() {
        return allocations.get();
    }

    private static int bucketIndexFor(int bytes) {
        int shift = Integer.SIZE - Integer.numberOfLeadingZeros(bytes - 1);
        return Math.max(shift - MIN_SHIFT, 0);
    }
}
