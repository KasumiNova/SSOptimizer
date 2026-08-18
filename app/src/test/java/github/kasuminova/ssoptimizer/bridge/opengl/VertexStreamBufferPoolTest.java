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
        // 大块且被池持有（v44b 实测内存涨至 7.9GB OOM）。修复后从需求档向下
        // 找：不足需求的缓冲入本地栈供更小需求，补货仍空则新建满足需求的——
        // acquire 返回值保证容量 >= 请求（编码端不再触发渐进扩容）
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        byte[] small = pool.acquire(VertexStreamBufferPool.MIN_CAPACITY);
        pool.release(small);
        byte[] fromPool = pool.acquire(VertexStreamBufferPool.MIN_CAPACITY * 8);
        assertTrue(fromPool.length >= VertexStreamBufferPool.MIN_CAPACITY * 8,
                "补货找不到合适缓冲时必须新建满足需求的容量");
        // 不足需求的小缓冲入本地栈，供更小需求复用
        assertSame(small, pool.acquire(VertexStreamBufferPool.MIN_CAPACITY),
                "不足需求的小缓冲入栈后供更小需求命中");
    }

    @Test
    void localPrefetchServesConsecutiveAcquiresWithoutNewAllocation() {
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        int batch = 32;
        // 直接灌入 32 个同档缓冲进全局池（模拟渲染线程归还的稳态库存）
        for (int i = 0; i < batch; i++) {
            pool.release(new byte[VertexStreamBufferPool.MIN_CAPACITY]);
        }
        assertEquals(batch, pool.pooledBufferCount());
        int before = pool.totalAllocations();

        // 借出 32 个：首次触发批量预借（池 32 全进栈 → 取 1），其余 31 次命中本地栈
        for (int i = 0; i < batch; i++) {
            byte[] buffer = pool.acquire(VertexStreamBufferPool.MIN_CAPACITY);
            assertTrue(buffer.length >= VertexStreamBufferPool.MIN_CAPACITY);
        }
        assertEquals(0, pool.pooledBufferCount(), "32 个借出应全部覆盖预借栈 + 池库存");
        assertEquals(before, pool.totalAllocations(), "预借栈命中时借出不得新建缓冲");
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
