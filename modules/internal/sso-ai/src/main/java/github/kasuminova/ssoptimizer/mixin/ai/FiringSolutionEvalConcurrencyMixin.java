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
 * 为敌船武器组和武器列表的并行迭代创建快照。
 * 快照拷贝遇到并发修改时由 {@link SnapshotRetry} 有界重试，耗尽后重抛异常。
 */
@Mixin(targets = GameClassNames.FIRING_SOLUTION_EVAL)
public abstract class FiringSolutionEvalConcurrencyMixin {

    @Redirect(method = "computeIncomingWeaponDamage",
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/combat/entities/Ship;getGroups()Ljava/util/List;"),
            remap = false)
    private static List<WeaponGroup> ssoptimizer$snapshotWeaponGroups(Ship ship) {
        return SnapshotRetry.snapshotWithRetry(ship::getGroups, "敌船武器组");
    }

    @Redirect(method = "computeIncomingWeaponDamage",
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/combat/systems/WeaponGroup;getWeapons()Ljava/util/List;"),
            remap = false)
    private static List<Weapon> ssoptimizer$snapshotWeapons(WeaponGroup group) {
        return SnapshotRetry.snapshotWithRetry(group::getWeapons, "武器组武器");
    }
}
