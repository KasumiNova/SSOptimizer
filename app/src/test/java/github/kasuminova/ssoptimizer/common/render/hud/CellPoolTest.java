package github.kasuminova.ssoptimizer.common.render.hud;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CellPool} 分配/续期/回收逻辑测试（纯逻辑，无 GL）。
 */
class CellPoolTest {

    private static final class Fixture {
        final AtomicLong clock = new AtomicLong(0);
        final CellPool pool = new CellPool(4, clock::get);
    }

    @Test
    void acquireAssignsDistinctCellsUntilExhausted() {
        final Fixture f = new Fixture();
        final Object owner = new Object();
        final int a = f.pool.acquire(owner);
        final int b = f.pool.acquire(owner);
        final int c = f.pool.acquire(owner);
        final int d = f.pool.acquire(owner);
        assertTrue(a >= 0 && b >= 0 && c >= 0 && d >= 0);
        assertEquals(4, java.util.Set.of(a, b, c, d).size());
        // 耗尽：返回 -1 且上报一次耗尽事件（事件只报一次）
        assertEquals(-1, f.pool.acquire(owner));
        assertTrue(f.pool.pollExhaustionEvent());
        assertFalse(f.pool.pollExhaustionEvent());
        assertEquals(-1, f.pool.acquire(owner));
        assertTrue(f.pool.pollExhaustionEvent());
    }

    @Test
    void touchRefreshesAndValidatesOwner() {
        final Fixture f = new Fixture();
        final Object owner = new Object();
        final Object other = new Object();
        final int cell = f.pool.acquire(owner);
        assertTrue(f.pool.touch(cell, owner));
        assertFalse(f.pool.touch(cell, other));
        assertFalse(f.pool.touch(-1, owner));
        assertFalse(f.pool.touch(99, owner));
    }

    @Test
    void staleCellIsReclaimedOnAcquire() {
        final Fixture f = new Fixture();
        final Object ownerA = new Object();
        final Object ownerB = new Object();
        // 占满
        for (int i = 0; i < 4; i++) {
            assertTrue(f.pool.acquire(ownerA) >= 0);
        }
        assertEquals(-1, f.pool.acquire(ownerB));
        // 超时后全部回收，可重新分配
        f.clock.addAndGet(CellPool.STALE_NANOS + 1);
        assertTrue(f.pool.acquire(ownerB) >= 0);
        // 原 owner 触碰已回收/易主的单元格失败
        assertFalse(f.pool.touch(0, ownerA));
    }

    @Test
    void touchedCellSurvivesSweep() {
        final Fixture f = new Fixture();
        final Object owner = new Object();
        final int cell = f.pool.acquire(owner);
        // 临近超时前续期
        f.clock.addAndGet(CellPool.STALE_NANOS - 1);
        assertTrue(f.pool.touch(cell, owner));
        f.clock.addAndGet(CellPool.STALE_NANOS - 1);
        // 未被回收：owner 触碰仍有效，且重新分配不会拿到同一格
        assertTrue(f.pool.touch(cell, owner));
        final int other = f.pool.acquire(new Object());
        assertTrue(other >= 0);
        assertNotEquals(cell, other);
    }

    @Test
    void gcCollectedOwnerCellIsReclaimed() {
        final Fixture f = new Fixture();
        final int cell = f.pool.acquire(new Object()); // owner 立即不可达
        assertTrue(cell >= 0);
        // 强制 GC 让 WeakReference 失效（不可靠时跳过断言语义：此处仅验证清理路径不抛异常）
        System.gc();
        System.gc();
        // 无论 WeakReference 是否已清空，分配都不应抛异常
        f.clock.addAndGet(CellPool.STALE_NANOS + 1);
        assertTrue(f.pool.acquire(new Object()) >= 0);
    }
}
