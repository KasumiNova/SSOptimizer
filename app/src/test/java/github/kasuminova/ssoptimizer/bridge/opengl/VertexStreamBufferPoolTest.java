package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VertexStreamBufferPool} 的借还语义验证：空池新建、归还复用、
 * 幂次分档匹配、超上限非池化缓冲的安全丢弃。
 */
class VertexStreamBufferPoolTest {

    @Test
    void acquireCreatesWhenEmptyAndReleaseEnablesReuse() {
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        byte[] first = pool.acquire(VertexStreamBufferPool.MIN_CAPACITY);
        assertEquals(VertexStreamBufferPool.MIN_CAPACITY, first.length, "空池新建按档位容量分配");
        assertEquals(0, pool.pooledBufferCount());
        pool.release(first);
        assertEquals(1, pool.pooledBufferCount());
        assertSame(first, pool.acquire(VertexStreamBufferPool.MIN_CAPACITY), "归还后再次借出必须复用同一缓冲");
        assertEquals(0, pool.pooledBufferCount());
    }

    @Test
    void requestRoundsUpToNextPowerOfTwoBucket() {
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        byte[] small = pool.acquire(VertexStreamBufferPool.MIN_CAPACITY);
        byte[] large = pool.acquire(VertexStreamBufferPool.MIN_CAPACITY + 1);
        assertEquals(VertexStreamBufferPool.MIN_CAPACITY * 2, large.length, "超出当前档容量应向上取整");
        assertTrue(large.length >= VertexStreamBufferPool.MIN_CAPACITY + 1);

        pool.release(small);
        pool.release(large);
        // 归还后按容量各归各档
        assertSame(small, pool.acquire(1));
        assertSame(large, pool.acquire(VertexStreamBufferPool.MIN_CAPACITY + 1));
    }

    @Test
    void emptyPoolAllocatesCapacityMeetingRequest() {
        // 空池（无任何归还缓冲）时新建的缓冲必须满足容量请求
        for (int requested : new int[]{1, 511, 512, 513, 4096, 4097, 65536, 1000000}) {
            VertexStreamBufferPool pool = new VertexStreamBufferPool();
            byte[] buffer = pool.acquire(requested);
            assertTrue(buffer.length >= requested, "空池新建缓冲容量必须满足请求 " + requested);
        }
    }

    @Test
    void acquireReusesAnyBucketBelowRequestWhenTargetBucketEmpty() {
        // 旧实现从需求档向上找：低档归还的缓冲永远不命中大需求，导致每帧新建
        // 大块且被池持有（v44b 实测内存涨至 7.9GB OOM）。修复后从需求档向下找，
        // 任何归还缓冲都能复用，容量不足由编码端 ensure 渐进扩容
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        byte[] small = pool.acquire(VertexStreamBufferPool.MIN_CAPACITY);
        pool.release(small);
        byte[] fromLowerBucket = pool.acquire(VertexStreamBufferPool.MIN_CAPACITY * 8);
        assertSame(small, fromLowerBucket, "目标档为空时必须复用低档归还的缓冲");
        // 归还后仍按实际容量入低档，后续小需求可再次命中
        pool.release(fromLowerBucket);
        assertSame(small, pool.acquire(VertexStreamBufferPool.MIN_CAPACITY));
    }

    @Test
    void oversizedBufferIsNotPooledButReleasesSafely() {
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        byte[] huge = pool.acquire(VertexStreamBufferPool.MAX_CAPACITY + 1);
        assertTrue(huge.length >= VertexStreamBufferPool.MAX_CAPACITY + 1);
        pool.release(huge);
        assertEquals(0, pool.pooledBufferCount(), "超过池化上限的缓冲不应入池");
    }

    @Test
    void smallBufferBelowMinBucketIsNotPooled() {
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        pool.release(new byte[16]);
        assertEquals(0, pool.pooledBufferCount(), "低于最小档容量的缓冲不应入池");
    }
}
