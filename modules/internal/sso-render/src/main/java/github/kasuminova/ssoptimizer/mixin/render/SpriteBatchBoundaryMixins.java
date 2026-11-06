package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatch;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 非 sprite 绘制入口必须先提交已累积批次，保持相对绘制顺序。 */
public final class SpriteBatchBoundaryMixins {

    private SpriteBatchBoundaryMixins() {
    }

    @Mixin(targets = GameClassNames.SHIP_DOTTED)
    public abstract static class ShipBoundary {
        @Inject(
                method = "render(Lcom/fs/starfarer/api/combat/CombatEngineLayers;Lcom/fs/starfarer/combat/CombatViewport;)V",
                at = @At("HEAD"), remap = false)
        private void ssoptimizer$flushSpriteBatch(CallbackInfo ci) {
            SpriteBatch.getInstance().flushPending();
        }
    }

    @Mixin(targets = GameClassNames.CUSTOM_COMBAT_ENTITY_DOTTED)
    public abstract static class CombatPluginBoundary {
        @Inject(
                method = "render(Lcom/fs/starfarer/api/combat/CombatEngineLayers;Lcom/fs/starfarer/combat/CombatViewport;)V",
                at = @At("HEAD"), remap = false)
        private void ssoptimizer$flushSpriteBatch(CallbackInfo ci) {
            SpriteBatch.getInstance().flushPending();
        }
    }

    @Mixin(targets = GameClassNames.DECAL_RENDERER_DOTTED)
    public abstract static class DecalBoundary {
        @Inject(method = "render(Lcom/fs/graphics/Sprite;F)V", at = @At("HEAD"), remap = false)
        private void ssoptimizer$flushSpriteBatch(CallbackInfo ci) {
            SpriteBatch.getInstance().flushPending();
        }
    }

    @Mixin(targets = GameClassNames.WEAPON_DAMAGE_EFFECT_DOTTED)
    public abstract static class WeaponDamageBoundary {
        @Inject(method = "beginDamageRender(FFF)V", at = @At("HEAD"), remap = false)
        private void ssoptimizer$flushSpriteBatch(CallbackInfo ci) {
            SpriteBatch.getInstance().flushPending();
        }
    }

    @Mixin(targets = GameClassNames.WEAPON_DAMAGE_EFFECT_DOTTED)
    public abstract static class WeaponDamageDecalBoundary {
        @Inject(method = "renderDamageDecals(FFFF)V", at = @At("HEAD"), remap = false)
        private void ssoptimizer$flushSpriteBatch(CallbackInfo ci) {
            SpriteBatch.getInstance().flushPending();
        }
    }
}
