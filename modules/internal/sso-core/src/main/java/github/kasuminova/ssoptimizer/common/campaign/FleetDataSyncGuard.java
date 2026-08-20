package github.kasuminova.ssoptimizer.common.campaign;

import com.fs.starfarer.campaign.fleet.FleetData;
import org.apache.log4j.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 舰队数据同步卡死的诊断与自愈守卫。
 * <p>
 * 动机：原版 {@code FleetData#syncIfNeeded()} 先置 {@code needsSync=false; forceNoSync=true}
 * 再执行同步体（成员列表重建、属性重算、模组 hullmod 回调），<b>全程没有 try/finally</b>——
 * 同步体内任何异常（典型：模组 hullmod 的 onFleetSync、Stats 计算链路）都会让
 * {@code forceNoSync} 永久停留在 true，此后所有同步分支被跳过：增删船只只写底层
 * members 列表，视图快照（membersWithoutNull 等）永不重建，表现为「舰队列表变成
 * 固定集合，添加/移除船只不生效」。
 * <p>
 * 本类由 FleetDataSyncGuardMixin 以 HEAD 注入与 PUTFIELD 重定向驱动：
 * <ul>
 *   <li>{@code forceNoSync} 在 syncIfNeeded 内的每次写入都记录写入点（线程+时间戳+栈）；</li>
 *   <li>syncIfNeeded 入口检测到「非嵌套调用但 forceNoSync 卡为 true」时，输出 ERROR
 *       日志（附原始写入点栈，用于定位同步体内的异常来源）并自愈恢复同步能力。</li>
 * </ul>
 * 性能设计：嵌套判定走「写入点年龄」快速路径——写入点新于 {@link #STUCK_GRACE_NANOS}
 * 时同线程调用必为活跃同步体内的嵌套调用，直接放行；仅写入点超龄（疑似卡死或
 * 超长同步）时才做栈遍历。同步体内的嵌套 syncIfNeeded 调用极高频（getMembers
 * 链路逐次触发），全量栈遍历曾在性能采样中占主线程约 28%。
 * 同步正常完成时（forceNoSync 写回 false）守卫记录即清除；外部经由
 * {@code setForceNoSync(true)} 的合法临时持有（如技能描述计算）不产生守卫记录，
 * 不会被误判。
 * <p>
 * 注意：本类必须位于 mixin 包之外——Mixin 注入体会被内联进目标类，mixin 包内的类
 * 不允许被变换后的游戏类直接引用（IllegalClassLoadError）。
 */
public final class FleetDataSyncGuard {
    private static final Logger LOGGER = Logger.getLogger(FleetDataSyncGuard.class);

    /** forceNoSync=true 写入点记录（按 FleetData 实例，弱引用不阻碍回收）。 */
    private static final Map<FleetData, GuardWritePoint> GUARD_WRITE_POINTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 卡死判定宽限期：写入点年龄小于该值时，同线程调用必为活跃同步体内的嵌套调用，
     * 直接放行不做栈遍历。取值需大于任何合理的单次同步耗时（含巨型模组舰队），
     * 同时远小于「崩溃后游戏继续运行到下一次顶层 syncIfNeeded」的典型间隔。
     */
    private static final long STUCK_GRACE_NANOS = 2_000_000_000L; // 2s

    private FleetDataSyncGuard() {
    }

    /**
     * syncIfNeeded 入口守卫：检测 forceNoSync 卡死并自愈。
     *
     * @param self        FleetData 实例
     * @param forceNoSync 当前 forceNoSync 值
     */
    public static void detectStuckSync(final FleetData self, final boolean forceNoSync) {
        if (!forceNoSync) {
            return;
        }
        final GuardWritePoint writePoint = GUARD_WRITE_POINTS.get(self);
        if (writePoint == null) {
            // 外部 setForceNoSync(true) 的合法临时持有，不在守卫范围
            return;
        }
        if (writePoint.thread != Thread.currentThread()) {
            // 其他线程的调用与本写入点无关
            return;
        }
        if (System.nanoTime() - writePoint.writeNanos < STUCK_GRACE_NANOS) {
            // 写入点很新：同线程只可能是活跃同步体内的嵌套 syncIfNeeded
            // （同步刚起步，或崩溃发生在毫秒级窗口内——后者自愈顺延到宽限期后的
            // 下一次调用，不影响正确性）。此快速路径避免嵌套调用逐次走栈遍历
            // （getStackTrace 曾在大型舰队同步中占主线程约 28%）。
            return;
        }
        if (isNestedSyncCall()) {
            // 超长的活跃同步体（巨型模组舰队）：宽限期后仍需栈遍历确认非嵌套
            return;
        }
        GUARD_WRITE_POINTS.remove(self);
        self.setForceNoSync(false);
        LOGGER.error("[SSOptimizer] 检测到 FleetData.syncIfNeeded 曾被异常打断（forceNoSync 卡死），"
                + "已自愈恢复舰队同步。forceNoSync 原始写入点栈如下（其下方即异常中断的同步体调用链方向）：",
                writePoint.writeStack);
    }

    /**
     * forceNoSync 写入守卫：true 记录写入点，false 清除记录。
     */
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

    /**
     * 判断当前调用是否为同步体内的嵌套 syncIfNeeded（栈上出现第二个 syncIfNeeded 帧）。
     * 守卫的 HEAD 注入本身即处在一次 syncIfNeeded 调用内，故顶层调用栈中
     * syncIfNeeded 恰出现一次。
     * <p>
     * 栈遍历开销大，仅在写入点年龄超过 {@link #STUCK_GRACE_NANOS} 的罕见路径上调用
     * （见 {@link #detectStuckSync} 的快速路径）。
     */
    private static boolean isNestedSyncCall() {
        int occurrences = 0;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if ("syncIfNeeded".equals(frame.getMethodName()) && ++occurrences > 1) {
                return true;
            }
        }
        return false;
    }

    /** forceNoSync=true 的写入点证据。 */
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
