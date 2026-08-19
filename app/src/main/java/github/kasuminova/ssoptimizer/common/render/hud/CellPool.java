package github.kasuminova.ssoptimizer.common.render.hud;

import java.lang.ref.WeakReference;
import java.util.function.LongSupplier;

/**
 * 雷达合成缓存的单元格分配器（纯逻辑，不触碰 GL，可单测）。
 * <p>
 * 语义：{@link #acquire} 分配空闲单元格（先惰性回收超时/owner 已 GC 的单元格）；
 * {@link #touch} 按 owner 校验并续期；耗尽返回 -1 并由 {@link #pollExhaustionEvent()}
 * 上报一次耗尽事件（供调用方记日志，避免每帧刷警告）。
 */
final class CellPool {
    /** 单元格超时被回收的间隔（纳秒）。 */
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

    /**
     * 分配一个空闲单元格（先惰性回收超时单元格）。
     *
     * @param owner 持有方（弱引用持有）
     * @return 单元格编号；耗尽返回 -1
     */
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

    /**
     * 触碰续期。
     *
     * @return false 表示单元格未分配/已回收/已易主
     */
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

    /**
     * 取出并清除一次耗尽事件（无事件返回 false）。
     *
     * @return 自上轮调用以来是否发生过分配耗尽
     */
    boolean pollExhaustionEvent() {
        final boolean event = exhaustionPending;
        exhaustionPending = false;
        return event;
    }
}
