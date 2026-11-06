package github.kasuminova.ssoptimizer.common.render.hud;

import java.lang.ref.WeakReference;
import java.util.function.LongSupplier;

final class CellPool {
    static final long STALE_NANOS = 1_000_000_000L;

    private final int cellCount;
    private final LongSupplier clock;
    private final WeakReference<Object>[] owners;
    private final long[] lastTouch;
    private boolean exhaustionPending;

    @SuppressWarnings("unchecked")
    CellPool(final int cellCount, final LongSupplier clock) {
        this.cellCount = cellCount;
        this.clock = clock;
        this.owners = new WeakReference[cellCount];
        this.lastTouch = new long[cellCount];
    }

    int acquire(final Object owner) {
        final long now = clock.getAsLong();
        for (int cell = 0; cell < cellCount; cell++) {
            if (owners[cell] != null && (owners[cell].get() == null || now - lastTouch[cell] > STALE_NANOS)) {
                owners[cell] = null;
                exhaustionPending = false;
            }
        }
        for (int cell = 0; cell < cellCount; cell++) {
            if (owners[cell] == null) {
                owners[cell] = new WeakReference<>(owner);
                lastTouch[cell] = now;
                return cell;
            }
        }
        exhaustionPending = true;
        return -1;
    }

    boolean touch(final int cell, final Object owner) {
        if (cell < 0 || cell >= cellCount) {
            return false;
        }
        final WeakReference<Object> ref = owners[cell];
        if (ref == null || ref.get() != owner) {
            return false;
        }
        lastTouch[cell] = clock.getAsLong();
        return true;
    }

    boolean pollExhaustionEvent() {
        final boolean event = exhaustionPending;
        exhaustionPending = false;
        return event;
    }
}
