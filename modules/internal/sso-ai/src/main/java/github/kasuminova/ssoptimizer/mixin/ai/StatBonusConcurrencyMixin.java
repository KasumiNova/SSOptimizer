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
 * 为 {@code StatBonus.recompute} 的集合迭代提供快照，并将 bonuses 表替换为同步快照映射。
 * 前者避免迭代与写入冲突，后者防止并发写入损坏表结构，同时保留空键和空值语义。
 */
@Mixin(targets = GameClassNames.STAT_BONUS)
public abstract class StatBonusConcurrencyMixin {

    @Redirect(method = "recompute",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/LinkedHashMap;values()Ljava/util/Collection;"),
            remap = false)
    private static Collection<MutableStat.StatMod> ssoptimizer$snapshotStatMods(
            LinkedHashMap<String, MutableStat.StatMod> bonuses) {
        return SnapshotRetry.snapshotWithRetry(bonuses::values, "StatBonus mods");
    }

    @Redirect(method = {"getFlatBonuses", "getPercentBonuses", "getMultBonuses", "readResolve"},
            at = @At(value = "NEW", target = "java/util/LinkedHashMap"),
            remap = false)
    private static LinkedHashMap<String, MutableStat.StatMod> ssoptimizer$newSyncBonusMap() {
        return new SyncStatBonusMap();
    }
}
