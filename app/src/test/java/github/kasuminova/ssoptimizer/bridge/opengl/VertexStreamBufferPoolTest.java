package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

    @Test
    void retentionIsBoundedByPeakDemandAndDoesNotGrowAcrossRounds() {
        // 无界保留回归点（JProfiler 实机 dump：池内 byte[] 6,335 MB）：高峰借出后
        // 池保留量收敛于 peak + slack，相同需求量反复借还不随轮次增长。
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        final int size = VertexStreamBufferPool.MIN_CAPACITY;
        final List<byte[]> held = new ArrayList<>();

        for (int i = 0; i < 200; i++) {
            held.add(pool.acquire(size));
        }
        for (byte[] buffer : held) {
            pool.release(buffer);
        }
        final int afterPeak = pool.pooledBufferCount();
        assertTrue(afterPeak <= 200 + VertexStreamBufferPool.RETENTION_SLACK,
                "高峰借还后池保留量应 ≤ peak+slack，实际 " + afterPeak);

        for (int round = 0; round < 5; round++) {
            held.clear();
            for (int i = 0; i < 200; i++) {
                held.add(pool.acquire(size));
            }
            for (byte[] buffer : held) {
                pool.release(buffer);
            }
            assertTrue(pool.pooledBufferCount() <= 200 + VertexStreamBufferPool.RETENTION_SLACK,
                    "反复借还后保留量不得增长，round=" + round + " 实际 " + pool.pooledBufferCount());
        }
    }

    @Test
    void peakDecayDrainsExcessRetentionAndExcessReleaseIsDropped() {
        // 需求回落后峰值衰减：池主动排空超限库存；之后超限归还被丢弃并计数。
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        final int size = VertexStreamBufferPool.MIN_CAPACITY;
        final List<byte[]> held = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            held.add(pool.acquire(size));
        }
        for (byte[] buffer : held) {
            pool.release(buffer);
        }
        assertEquals(100, pool.pooledBufferCount());

        // 连续衰减把借出峰值收缩至 0：上限回落至 slack，池排空至 slack 以内
        for (int i = 0; i < 30; i++) {
            pool.decayPeaksForTest();
        }
        assertTrue(pool.pooledBufferCount() <= VertexStreamBufferPool.RETENTION_SLACK,
                "峰值衰减后池应排空至 slack 以内，实际 " + pool.pooledBufferCount());

        final long droppedBefore = pool.droppedBufferCount();
        pool.release(new byte[size]);
        assertTrue(pool.pooledBufferCount() <= VertexStreamBufferPool.RETENTION_SLACK,
                "超限归还应被丢弃而非继续滞留");
        assertTrue(pool.droppedBufferCount() > droppedBefore, "丢荒应计数");
    }

    @Test
    void steadyStateRetentionMatchesDemandWithoutDrops() {
        // 稳态不抖动：需求 D 时池保留量在 [D, D+slack] 内波动，且不触发丢荒
        // （保留上限 = 借出峰值 + slack 恒 ≥ 需求）。
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        final int size = VertexStreamBufferPool.MIN_CAPACITY;
        final int demand = 40;
        final List<byte[]> held = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            held.clear();
            for (int i = 0; i < demand; i++) {
                held.add(pool.acquire(size));
            }
            for (byte[] buffer : held) {
                pool.release(buffer);
            }
        }
        assertTrue(pool.pooledBufferCount() <= demand + VertexStreamBufferPool.RETENTION_SLACK,
                "稳态保留量应 ≤ 需求+slack，实际 " + pool.pooledBufferCount());
        // 稳态下 10 轮借还不应有任何丢荒（保留上限覆盖需求）
        assertEquals(0, pool.droppedBufferCount(), "稳态需求下不得丢荒重建");
    }
}
