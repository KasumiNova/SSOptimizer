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
 * 串行化船体变体 OP 开销属性的懒初始化，保护工作线程共享的两个非 volatile 字段。
 * 监视器边界也保证后续读取方看到完整初始化结果。
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
