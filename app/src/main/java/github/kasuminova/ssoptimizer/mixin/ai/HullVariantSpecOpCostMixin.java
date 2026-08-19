package github.kasuminova.ssoptimizer.mixin.ai;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.combat.entities.ship.ShipStats;
import com.fs.starfarer.loading.SpecStore;
import com.fs.starfarer.loading.specs.HullModSpec;
import com.fs.starfarer.loading.specs.HullVariantSpec;
import com.fs.starfarer.loading.specs.ShipHullSpec;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 船体变体 OP 开销属性懒初始化并发修复 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.loading.specs.HullVariantSpec#updateStatsForOpCosts}<br>
 * 注入动机：舰船 AI 并行后，同变体的多艘舰船可能在不同工作线程同时触发
 * {@code BehaviorModule.advance → CombatFleetManager.computeDeployedStrength →
 * getMemberStrength → computeOPCost}，首次进入该懒初始化时
 * {@code hasOpAffectingMods}/{@code statsForOpCosts} 两个非 volatile 字段被多线程
 * 交叉写回（一线程在循环内已被另一线程把标志重置，最终 stats 被置 null 后仍在
 * 应用 hullmod），长程基准实测在 RuggedConstruction 内抛出 NPE。<br>
 * 注入效果：方法体保持原版逻辑不变，改为 {@code synchronized}（变体实例级监视器）。
 * 所有读取方（computeWeaponOPCost/computeOPCost/getStatsForOpCosts）都先调用本方法
 * 再读字段，监视器边界提供完整的 happens-before，初始化后无竞争开销可忽略。
 */
@Mixin(targets = GameClassNames.HULL_VARIANT_SPEC_DOTTED)
public abstract class HullVariantSpecOpCostMixin {
    @Shadow(remap = false)
    private ShipStats statsForOpCosts;

    @Shadow(remap = false)
    private Boolean hasOpAffectingMods;

    @Shadow(remap = false)
    private ShipHullSpec hullSpec;

    @Shadow(remap = false)
    public abstract java.util.Collection<String> getHullMods();

    /**
     * @author KasumiNova
     * @reason 原版懒初始化非线程安全；逻辑逐行保持一致，仅加监视器串行化。
     */
    @Overwrite(remap = false)
    private synchronized void updateStatsForOpCosts() {
        if (this.hasOpAffectingMods == null) {
            this.statsForOpCosts = ShipStats.create((HullVariantSpec) (Object) this);
            this.hasOpAffectingMods = false;

            for (String modId : this.getHullMods()) {
                HullModSpec spec = SpecStore.getSpec(HullModSpec.class, modId);
                if (spec.getEffect().affectsOPCosts()) {
                    this.hasOpAffectingMods = true;
                    spec.getEffect().applyEffectsBeforeShipCreation(
                            this.hullSpec.getHullSize(), this.statsForOpCosts, spec.getId());
                }
            }

            if (!this.hasOpAffectingMods) {
                this.statsForOpCosts = null;
            }
        }
    }
}
