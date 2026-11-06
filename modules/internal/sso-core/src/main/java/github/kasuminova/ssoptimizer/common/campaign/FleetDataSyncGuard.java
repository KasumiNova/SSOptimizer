package github.kasuminova.ssoptimizer.common.campaign;

import com.fs.starfarer.campaign.fleet.FleetData;
import org.apache.log4j.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 诊断并恢复 {@code FleetData.syncIfNeeded()} 异常后遗留的 {@code forceNoSync=true} 状态。
 * 写入点未超过宽限期时视为嵌套同步；超龄后才遍历调用栈，避免高频嵌套调用重复取证。
 */
public final class FleetDataSyncGuard {
    private static final Logger LOGGER = Logger.getLogger(FleetDataSyncGuard.class);

    private static final Map<FleetData, GuardWritePoint> GUARD_WRITE_POINTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final long STUCK_GRACE_NANOS = 2_000_000_000L;

    private FleetDataSyncGuard() {
    }

    /** 非嵌套调用发现同步标志遗留时，记录原始写入点并恢复同步能力。 */
    public static void detectStuckSync(final FleetData self, final boolean forceNoSync) {
        if (!forceNoSync) {
            return;
        }
        final GuardWritePoint writePoint = GUARD_WRITE_POINTS.get(self);
        if (writePoint == null) {
            return;
        }
        if (writePoint.thread != Thread.currentThread()) {
            return;
        }
        if (System.nanoTime() - writePoint.writeNanos < STUCK_GRACE_NANOS) {
            return;
        }
        if (isNestedSyncCall()) {
            return;
        }
        GUARD_WRITE_POINTS.remove(self);
        self.setForceNoSync(false);
        LOGGER.error("[SSOptimizer] 检测到 FleetData.syncIfNeeded 曾被异常打断（forceNoSync 卡死），"
                + "已自愈恢复舰队同步。forceNoSync 原始写入点栈如下（其下方即异常中断的同步体调用链方向）：",
                writePoint.writeStack);
    }

    public static void recordForceNoSyncWrite(final FleetData self, final boolean value) {
        if (value) {
            GUARD_WRITE_POINTS.put(self, new GuardWritePoint(
                    Thread.currentThread(),
                    System.nanoTime(),
                    new Exception("[SSOptimizer] forceNoSync=true 写入点（非异常，仅用于栈追溯）")));
        } else {
            GUARD_WRITE_POINTS.remove(self);
        }
    }

    private static boolean isNestedSyncCall() {
        int occurrences = 0;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if ("syncIfNeeded".equals(frame.getMethodName()) && ++occurrences > 1) {
                return true;
            }
        }
        return false;
    }

    private static final class GuardWritePoint {
        final Thread    thread;
        final long      writeNanos;
        final Exception writeStack;

        GuardWritePoint(final Thread thread, final long writeNanos, final Exception writeStack) {
            this.thread = thread;
            this.writeNanos = writeNanos;
            this.writeStack = writeStack;
        }
    }
}
