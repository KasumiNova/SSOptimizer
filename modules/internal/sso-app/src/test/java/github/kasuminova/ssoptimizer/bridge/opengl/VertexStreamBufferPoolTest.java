package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        assertSame(small, pool.acquire(1));
        assertSame(large, pool.acquire(VertexStreamBufferPool.MIN_CAPACITY + 1));
    }

    @Test
    void emptyPoolAllocatesCapacityMeetingRequest() {
        for (int requested : new int[]{1, 511, 512, 513, 4096, 4097, 65536, 1000000}) {
            VertexStreamBufferPool pool = new VertexStreamBufferPool();
            byte[] buffer = pool.acquire(requested);
            assertTrue(buffer.length >= requested, "空池新建缓冲容量必须满足请求 " + requested);
        }
    }

    @Test
    void acquireReusesAnyBucketBelowRequestWhenTargetBucketEmpty() {
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        byte[] small = pool.acquire(VertexStreamBufferPool.MIN_CAPACITY);
        pool.release(small);
        byte[] fromPool = pool.acquire(VertexStreamBufferPool.MIN_CAPACITY * 8);
        assertTrue(fromPool.length >= VertexStreamBufferPool.MIN_CAPACITY * 8,
                "补货找不到合适缓冲时必须新建满足需求的容量");
        assertSame(small, pool.acquire(VertexStreamBufferPool.MIN_CAPACITY),
                "不足需求的小缓冲入栈后供更小需求命中");
    }

    @Test
    void localPrefetchServesConsecutiveAcquiresWithoutNewAllocation() {
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        int batch = 32;
        for (int i = 0; i < batch; i++) {
            pool.release(new byte[VertexStreamBufferPool.MIN_CAPACITY]);
        }
        assertEquals(batch, pool.pooledBufferCount());
        int before = pool.totalAllocations();

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
        assertEquals(0, pool.droppedBufferCount(), "稳态需求下不得丢荒重建");
    }

    @Test
    void acquireDrainsReturnInboxBeforeGlobalPool() {
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        org.jctools.queues.SpscArrayQueue<byte[]> inbox =
                new org.jctools.queues.SpscArrayQueue<>(VertexStreamBufferPool.RETURN_INBOX_CAPACITY);
        byte[] returned = new byte[VertexStreamBufferPool.MIN_CAPACITY];
        inbox.offer(returned);

        byte[] acquired = pool.acquire(VertexStreamBufferPool.MIN_CAPACITY, inbox);

        assertSame(returned, acquired, "SPSC inbox 返还的缓冲必须优先被借出");
        assertTrue(inbox.isEmpty(), "drain 后 inbox 应为空");
        assertEquals(0, pool.totalAllocations(), "inbox 命中不得新建缓冲");
    }

    @Test
    void inboxHeadFastPathReturnsSingleBufferLeavingRestForNextAcquire() {
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        org.jctools.queues.SpscArrayQueue<byte[]> inbox =
                new org.jctools.queues.SpscArrayQueue<>(VertexStreamBufferPool.RETURN_INBOX_CAPACITY);
        byte[] first = new byte[VertexStreamBufferPool.MIN_CAPACITY * 4];
        byte[] second = new byte[VertexStreamBufferPool.MIN_CAPACITY * 4];
        inbox.offer(first);
        inbox.offer(second);

        assertSame(first, pool.acquire(VertexStreamBufferPool.MIN_CAPACITY * 4, inbox),
                "快路径必须返回 inbox 首元素");
        assertEquals(1, inbox.size(), "快路径单元素 poll，余量留给下次借出");
        assertSame(second, pool.acquire(VertexStreamBufferPool.MIN_CAPACITY * 4, inbox),
                "下次借出继续命中直还缓冲");
        assertEquals(0, pool.totalAllocations(), "快路径全程不得新建缓冲");
        assertEquals(0, pool.pooledBufferCount(), "快路径不得触碰全局池");
    }

    @Test
    void undersizedInboxHeadFallsToLocalStackAndServesSmallerRequest() {
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        org.jctools.queues.SpscArrayQueue<byte[]> inbox =
                new org.jctools.queues.SpscArrayQueue<>(VertexStreamBufferPool.RETURN_INBOX_CAPACITY);
        byte[] small = new byte[VertexStreamBufferPool.MIN_CAPACITY];
        inbox.offer(small);

        byte[] large = pool.acquire(VertexStreamBufferPool.MIN_CAPACITY * 8, inbox);
        assertTrue(large.length >= VertexStreamBufferPool.MIN_CAPACITY * 8,
                "快路径未命中时必须经常规路径满足需求容量");
        assertTrue(inbox.isEmpty(), "未命中的 inbox 首元素必须被消费（入栈）");

        assertSame(small, pool.acquire(VertexStreamBufferPool.MIN_CAPACITY, inbox),
                "入栈的小缓冲必须供更小需求命中");
        assertEquals(1, pool.totalAllocations(), "仅升档新建一次，小缓冲复用不再新建");
    }

    @Test
    void inboxOverflowFallsBackToGlobalPoolWhenLocalStackFull() {
        VertexStreamBufferPool pool = new VertexStreamBufferPool();
        final int min = VertexStreamBufferPool.MIN_CAPACITY;
        for (int i = 0; i < 40; i++) {
            pool.release(new byte[min]);
        }
        pool.acquire(min);

        org.jctools.queues.SpscArrayQueue<byte[]> inbox =
                new org.jctools.queues.SpscArrayQueue<>(VertexStreamBufferPool.RETURN_INBOX_CAPACITY);
        inbox.offer(new byte[min]);
        inbox.offer(new byte[min]);

        pool.acquire(min * 2, inbox);

        assertTrue(inbox.isEmpty(), "drain 后 inbox 应为空");
        assertEquals(9, pool.pooledBufferCount(),
                "栈满时 inbox 溢出的缓冲必须落回全局池（8 存量 + 1 溢出），实际 " + pool.pooledBufferCount());
    }
}
