package github.kasuminova.ssoptimizer.common.render.hud;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        for (int i = 0; i < 4; i++) {
            assertTrue(f.pool.acquire(ownerA) >= 0);
        }
        assertEquals(-1, f.pool.acquire(ownerB));
        f.clock.addAndGet(CellPool.STALE_NANOS + 1);
        assertTrue(f.pool.acquire(ownerB) >= 0);
        assertFalse(f.pool.touch(0, ownerA));
    }

    @Test
    void touchedCellSurvivesSweep() {
        final Fixture f = new Fixture();
        final Object owner = new Object();
        final int cell = f.pool.acquire(owner);
        f.clock.addAndGet(CellPool.STALE_NANOS - 1);
        assertTrue(f.pool.touch(cell, owner));
        f.clock.addAndGet(CellPool.STALE_NANOS - 1);
        assertTrue(f.pool.touch(cell, owner));
        final int other = f.pool.acquire(new Object());
        assertTrue(other >= 0);
        assertNotEquals(cell, other);
    }

    @Test
    void gcCollectedOwnerCellIsReclaimed() {
        final Fixture f = new Fixture();
        final int cell = f.pool.acquire(new Object());
        assertTrue(cell >= 0);
        System.gc();
        System.gc();
        f.clock.addAndGet(CellPool.STALE_NANOS + 1);
        assertTrue(f.pool.acquire(new Object()) >= 0);
    }
}
