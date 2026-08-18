package github.kasuminova.ssoptimizer.mixin.campaign;

import com.fs.starfarer.campaign.fleet.FleetData;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.apache.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.objectweb.asm.Opcodes;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 舰队数据同步卡死的诊断与自愈 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.fleet.FleetData#syncIfNeeded()}<br>
 * 注入动机：原版 syncIfNeeded 先置 {@code needsSync=false; forceNoSync=true} 再执行
 * 同步体（成员列表重建、属性重算、模组 hullmod 回调），<b>全程没有 try/finally</b>——
 * 同步体内任何异常（典型：模组 hullmod 的 onFleetSync、Stats 计算链路）都会让
 * {@code forceNoSync} 永久停留在 true，此后所有同步分支被跳过：增删船只只写底层
 * members 列表，视图快照（membersWithoutNull 等）永不重建，表现为「舰队列表变成
 * 固定集合，添加/移除船只不生效」。<br>
 * 注入效果：
 * <ul>
 *   <li>{@code forceNoSync} 在 syncIfNeeded 内的每次写入都记录写入点（线程+栈）；</li>
 *   <li>syncIfNeeded 入口检测到「非嵌套调用但 forceNoSync 卡为 true」时，输出 ERROR
 *       日志（附原始写入点栈，用于定位同步体内的异常来源）并自愈恢复同步能力。</li>
 * </ul>
 * 同步正常完成时（forceNoSync 写回 false）守卫记录即清除；外部经由
 * {@code setForceNoSync(true)} 的合法临时持有（如技能描述计算）不产生守卫记录，
 * 不会被误判。
 */
@Mixin(targets = GameClassNames.FLEET_DATA_DOTTED, remap = false)
public abstract class FleetDataSyncGuardMixin {
    private static final Logger LOGGER = Logger.getLogger(FleetDataSyncGuardMixin.class);

    /** forceNoSync=true 写入点记录（按 FleetData 实例，弱引用不阻碍回收）。 */
    private static final Map<FleetData, GuardWritePoint> GUARD_WRITE_POINTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Shadow
    private boolean forceNoSync;

    @Inject(method = "syncIfNeeded", at = @At("HEAD"))
    private void ssoptimizer$detectStuckSync(final CallbackInfo ci) {
        if (!this.forceNoSync) {
            return;
        }
        FleetData self = (FleetData) (Object) this;
        GuardWritePoint writePoint = GUARD_WRITE_POINTS.get(self);
        if (writePoint == null) {
            // 外部 setForceNoSync(true) 的合法临时持有，不在守卫范围
            return;
        }
        if (writePoint.thread != Thread.currentThread() || isNestedSyncCall()) {
            // 同步体内部的嵌套 syncIfNeeded 调用：forceNoSync=true 是设计内行为
            return;
        }
        GUARD_WRITE_POINTS.remove(self);
        self.setForceNoSync(false);
        LOGGER.error("[SSOptimizer] 检测到 FleetData.syncIfNeeded 曾被异常打断（forceNoSync 卡死），"
                + "已自愈恢复舰队同步。forceNoSync 原始写入点栈如下（其下方即异常中断的同步体调用链方向）：",
                writePoint.writeStack);
    }

    @Redirect(method = "syncIfNeeded", remap = false,
            at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                    target = "Lcom/fs/starfarer/campaign/fleet/FleetData;forceNoSync:Z"))
    private void ssoptimizer$guardForceNoSyncWrite(final FleetData self, final boolean value) {
        if (value) {
            GUARD_WRITE_POINTS.put(self, new GuardWritePoint(
                    Thread.currentThread(),
                    new Exception("[SSOptimizer] forceNoSync=true 写入点（非异常，仅用于栈追溯）")));
        } else {
            GUARD_WRITE_POINTS.remove(self);
        }
        self.setForceNoSync(value);
    }

    /**
     * 判断当前调用是否为同步体内的嵌套 syncIfNeeded（栈上出现第二个 syncIfNeeded 帧）。
     * 本守卫的 HEAD 注入本身即处在一次 syncIfNeeded 调用内，故顶层调用栈中
     * syncIfNeeded 恰出现一次。
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
        final Exception writeStack;

        GuardWritePoint(final Thread thread, final Exception writeStack) {
            this.thread = thread;
            this.writeStack = writeStack;
        }
    }
}
