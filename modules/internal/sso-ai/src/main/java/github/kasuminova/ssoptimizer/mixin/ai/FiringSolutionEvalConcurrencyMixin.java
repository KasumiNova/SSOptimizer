package github.kasuminova.ssoptimizer.mixin.ai;

import com.fs.starfarer.combat.entities.Ship;
import com.fs.starfarer.combat.systems.Weapon;
import com.fs.starfarer.combat.systems.WeaponGroup;
import github.kasuminova.ssoptimizer.common.combat.ai.SnapshotRetry;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * FiringSolutionEval 敌船武器/武器组迭代的并发快照守卫。
 * <p>
 * 背景：AI 并行化后 worker 线程的 {@code FiringSolutionEval.advance} →
 * {@code computeIncomingWeaponDamage} 会遍历敌船武器系统与武器组：
 * {@code var19.addAll(var11.getGroups())}（敌船武器组活列表）与
 * {@code var41.getWeapons()} / {@code var39.getWeapons()}（武器组内武器
 * 活列表）——并行窗口期敌船武器增删（武器损毁/恢复/装填，主线程或该敌船
 * 自身 AI）时，遍历随并发写抛
 * {@link java.util.concurrent.ConcurrentModificationException}（与
 * StatBonus.recompute 同源的并行 AI 竞态形态）。
 * <p>
 * 修复：@Redirect 拦截 {@code computeIncomingWeaponDamage} 内的
 * {@code Ship.getGroups()} 与 {@code WeaponGroup.getWeapons()} 调用，经
 * {@link SnapshotRetry} 返回快照拷贝——迭代敌船武器的快照（迭代开始时刻
 * 的武器组合），不随并发增删抛 CME；快照创建撞上并发写时有界重试
 * （{@link SnapshotRetry#MAX_RETRIES} 次），耗尽仍 CME 时重抛并记 error。
 */
@Mixin(targets = GameClassNames.FIRING_SOLUTION_EVAL)
public abstract class FiringSolutionEvalConcurrencyMixin {

    /**
     * @param ship getGroups() 的调用目标（敌船）
     * @return 武器组列表的快照拷贝
     */
    @Redirect(method = "computeIncomingWeaponDamage",
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/combat/entities/Ship;getGroups()Ljava/util/List;"),
            remap = false)
    private static List<WeaponGroup> ssoptimizer$snapshotWeaponGroups(Ship ship) {
        return SnapshotRetry.snapshotWithRetry(ship::getGroups, "敌船武器组");
    }

    /**
     * @param group getWeapons() 的调用目标（武器组，可能为敌船原生组）
     * @return 武器列表的快照拷贝
     */
    @Redirect(method = "computeIncomingWeaponDamage",
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/combat/systems/WeaponGroup;getWeapons()Ljava/util/List;"),
            remap = false)
    private static List<Weapon> ssoptimizer$snapshotWeapons(WeaponGroup group) {
        return SnapshotRetry.snapshotWithRetry(group::getWeapons, "武器组武器");
    }
}
