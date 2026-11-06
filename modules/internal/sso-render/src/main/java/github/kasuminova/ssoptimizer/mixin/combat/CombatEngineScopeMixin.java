package github.kasuminova.ssoptimizer.mixin.combat;

import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatch;
import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatchStats;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = GameClassNames.COMBAT_ENGINE_DOTTED)
public abstract class CombatEngineScopeMixin {

    @Inject(method = "render(Z)V", at = @At("HEAD"), remap = false)
    private void ssoptimizer$spriteBatchScopeBegin(boolean innerRender, CallbackInfo ci) {
        SpriteBatchStats.beginCombatScope();
    }

    @Inject(method = "render(Z)V", at = @At("RETURN"), remap = false)
    private void ssoptimizer$spriteBatchScopeEnd(boolean innerRender, CallbackInfo ci) {
        SpriteBatch.getInstance().flushPending();
        SpriteBatchStats.endCombatScope();
    }
}
