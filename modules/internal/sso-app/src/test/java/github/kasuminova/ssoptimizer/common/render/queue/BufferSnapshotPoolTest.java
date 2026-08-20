package github.kasuminova.ssoptimizer.common.render.queue;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BufferSnapshotPoolImpl} 的行为验证：借用/归还复用、容量分级扩容、
 * 快照与源 buffer 的隔离性、非池化大块的安全释放。
 */
class BufferSnapshotPoolTest {

    @Test
    void releasedBufferIsReusedOnNextBorrowOfSameSize() {
        BufferSnapshotPoolImpl pool = new BufferSnapshotPoolImpl();
        ByteBuffer first = pool.borrow(100);
        assertEquals(BufferSnapshotPoolImpl.MIN_CAPACITY, first.capacity());
        assertEquals(0, first.position());
        assertEquals(first.capacity(), first.limit());
        pool.release(first);
        assertEquals(1, pool.pooledBufferCount());

        ByteBuffer second = pool.borrow(200);
        assertSame(first, second, "同容量级的借出应复用归还的池内缓冲");
        assertEquals(1, pool.totalAllocations(), "复用不应产生新分配");
    }

    @Test
    void largerRequestRoundsUpToNextPowerOfTwoBucket() {
        BufferSnapshotPoolImpl pool = new BufferSnapshotPoolImpl();
        ByteBuffer small = pool.borrow(BufferSnapshotPoolImpl.MIN_CAPACITY);
        ByteBuffer large = pool.borrow(BufferSnapshotPoolImpl.MIN_CAPACITY + 1);
        assertEquals(BufferSnapshotPoolImpl.MIN_CAPACITY * 2, large.capacity(), "超出当前桶容量应向上取整扩容");
        assertEquals(2, pool.totalAllocations());

        pool.release(small);
        pool.release(large);
        assertEquals(2, pool.pooledBufferCount());
        // 归还后按容量各归各桶
        assertSame(small, pool.borrow(1));
        assertSame(large, pool.borrow(BufferSnapshotPoolImpl.MIN_CAPACITY + 1));
    }

    @Test
    void oversizedBufferIsNotPooledButReleasesSafely() {
        BufferSnapshotPoolImpl pool = new BufferSnapshotPoolImpl();
        ByteBuffer huge = pool.borrow(BufferSnapshotPoolImpl.MAX_CAPACITY + 1);
        assertTrue(huge.capacity() >= BufferSnapshotPoolImpl.MAX_CAPACITY + 1);
        pool.release(huge);
        assertEquals(0, pool.pooledBufferCount(), "超过池化上限的缓冲不应入池");
    }

    @Test
    void snapshotIsIsolatedFromSourceMutation() {
        BufferSnapshotPoolImpl pool = new BufferSnapshotPoolImpl();
        ByteBuffer src = ByteBuffer.allocateDirect(16);
        for (int i = 0; i < 16; i++) {
            src.put((byte) (i + 1));
        }
        src.flip();

        ByteBuffer snapshot = pool.snapshot(src);
        assertEquals(0, snapshot.position());
        assertEquals(16, snapshot.limit());
        // 录制后改写源 buffer（模拟调用方复用）
        src.clear();
        for (int i = 0; i < 16; i++) {
            src.put((byte) 0x7F);
        }

        for (int i = 0; i < 16; i++) {
            assertEquals((byte) (i + 1), snapshot.get(i), "快照必须保持录制时刻的内容");
        }
        pool.release(snapshot);
    }

    @Test
    void typedSnapshotPreservesElementOrderAndByteOrder() {
        BufferSnapshotPoolImpl pool = new BufferSnapshotPoolImpl();
        FloatBuffer src = ByteBuffer.allocateDirect(4 * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer();
        src.put(new float[]{1.5f, -2.25f, 3.75f, 4f});
        src.flip();

        ByteBuffer snapshot = pool.snapshot(src);
        assertEquals(ByteOrder.LITTLE_ENDIAN, snapshot.order(), "快照必须保留源字节序");
        assertEquals(4 * Float.BYTES, snapshot.remaining());
        FloatBuffer view = snapshot.asFloatBuffer();
        assertEquals(4, view.remaining());
        assertEquals(1.5f, view.get(0));
        assertEquals(-2.25f, view.get(1));
        assertEquals(3.75f, view.get(2));
        assertEquals(4f, view.get(3));

        IntBuffer intSrc = ByteBuffer.allocateDirect(2 * Integer.BYTES).asIntBuffer();
        intSrc.put(new int[]{42, -7});
        intSrc.flip();
        ByteBuffer intSnapshot = pool.snapshot(intSrc);
        assertEquals(2, intSnapshot.asIntBuffer().remaining());
        assertEquals(-7, intSnapshot.asIntBuffer().get(1));
    }

    @Test
    void emptySourceSnapshotsToZeroLengthBuffer() {
        BufferSnapshotPoolImpl pool = new BufferSnapshotPoolImpl();
        ByteBuffer snapshot = pool.snapshot(ByteBuffer.allocateDirect(0));
        assertEquals(0, snapshot.remaining());
        pool.release(snapshot);
    }
}
