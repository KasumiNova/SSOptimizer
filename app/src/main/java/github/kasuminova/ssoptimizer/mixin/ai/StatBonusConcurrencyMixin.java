package github.kasuminova.ssoptimizer.mixin.ai;

import com.fs.starfarer.api.combat.MutableStat;
import github.kasuminova.ssoptimizer.common.combat.ai.SnapshotRetry;
import github.kasuminova.ssoptimizer.common.combat.ai.SyncStatBonusMap;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;
import java.util.LinkedHashMap;

/**
 * StatBonus 惰性重算的并发快照守卫。
 * <p>
 * 背景：AI 并行化后 worker 线程的 FiringSolutionEval/BasicShipAI 链路
 * （getRange → ShipStats.computeExtraRangeMult → StatBonus.getPercentMod）
 * 触发 {@code recompute()}——方法在 {@code needsRecompute} 为 true 时遍历
 * 三个 {@link LinkedHashMap}（percent/flat/mult bonuses）的 {@code values()}；
 * 并行窗口期另一线程（主线程 mod 系统更新、或其他 worker 触发的 stat 变更）
 * 对同一 map 并发 put/remove，遍历随并发写抛
 * {@link java.util.concurrent.ConcurrentModificationException}
 * （压测实测：StatBonus.recompute → getPercentMod → computeExtraRangeMult →
 * MissileWeapon.getRange → FiringSolutionEval.computeIncomingWeaponDamage，
 * SSOptimizer-AI-Worker 线程崩溃退出）。
 * <p>
 * 修复：@Redirect 拦截 {@code recompute()} 内全部
 * {@code LinkedHashMap.values()} 调用，经 {@link SnapshotRetry} 返回快照
 * 拷贝——recompute 对快照迭代，不受并发写影响（快照 = 迭代开始时刻的 mods
 * 组合；percent 求和 / flat 求和 / mult 连乘与迭代顺序无关，语义等价）。
 * 快照创建本身撞上并发写时有界重试（{@link SnapshotRetry#MAX_RETRIES} 次，
 * 正常路径零 CME 零开销）；重试耗尽仍 CME 时重抛异常并记 error——不吞异常。
 * <p>
 * 结构级修复：快照守卫解决不了 put+put 并发写损坏哈希桶链表（压测实测
 * values() 产出 null 元素 → recompute NPE "Cannot read field value because
 * mod is null"）。三个 bonuses 表的全部四个分配点（三个惰性 getter +
 * readResolve）重定向为 {@link SyncStatBonusMap}——单操作实例锁同步 +
 * 视图快照拷贝，从结构上杜绝并发写损坏；保持 LinkedHashMap 子类与 null
 * 语义，兼容模组直接持表读写。
 */
@Mixin(targets = GameClassNames.STAT_BONUS)
public abstract class StatBonusConcurrencyMixin {

    /**
     * @param bonuses 被 recompute 遍历的 mod 集合（LinkedHashMap 活引用）
     * @return mods 的快照拷贝（并发写期间迭代稳定，不抛 CME）
     */
    @Redirect(method = "recompute",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/LinkedHashMap;values()Ljava/util/Collection;"),
            remap = false)
    private static Collection<MutableStat.StatMod> ssoptimizer$snapshotStatMods(
            LinkedHashMap<String, MutableStat.StatMod> bonuses) {
        return SnapshotRetry.snapshotWithRetry(bonuses::values, "StatBonus mods");
    }

    /**
     * 三个 bonuses 表的实例化点统一换成 {@link SyncStatBonusMap}（结构级并发安全）。
     * <p>
     * 快照守卫解决「迭代遇并发写」的 CME，但解决不了 put+put 并发导致的哈希桶
     * 链表损坏（压测实测 values() 产出 null 元素 → recompute NPE）。
     * 覆盖全部四个分配点：三个惰性 getter 与 XStream 反序列化的 readResolve。
     *
     * @return 同步化 + 视图快照化的 LinkedHashMap 子类实例
     */
    @Redirect(method = {"getFlatBonuses", "getPercentBonuses", "getMultBonuses", "readResolve"},
            at = @At(value = "NEW", target = "java/util/LinkedHashMap"),
            remap = false)
    private static LinkedHashMap<String, MutableStat.StatMod> ssoptimizer$newSyncBonusMap() {
        return new SyncStatBonusMap();
    }
}
