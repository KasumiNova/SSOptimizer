package github.kasuminova.ssoptimizer.mixin.combat;

import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.combat.CombatViewport;
import com.fs.starfarer.combat.systems.EmpSystem;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 渲染与逻辑线程并发时 target 可能短暂为 null，必须跳过该帧。 */
@Mixin(targets = GameClassNames.EMP_ARC_ENTITY_DOTTED)
public abstract class EmpArcEntityRenderMixin {
    @Shadow(remap = false)
    private EmpSystem.EmpArcTarget target;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$guardNullTarget(final CombatEngineLayers layer,
                                             final CombatViewport viewport,
                                             final CallbackInfo ci) {
        if (this.target == null) {
            ci.cancel();
        }
    }
}
