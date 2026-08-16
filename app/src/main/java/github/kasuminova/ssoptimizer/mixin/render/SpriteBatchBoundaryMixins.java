package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatch;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sprite 合批顺序边界 Mixin 集合：在非 sprite 绘制入口把已累积的批次立即绘制，
 * 保证流式合批的相对绘制顺序与原版逐位一致。
 * <p>
 * 覆盖边界：
 * <ul>
 *   <li>{@code Ship.render(CombatEngineLayers, CombatViewport)} —— 舰间边界；</li>
 *   <li>{@code CustomCombatEntity.render(CombatEngineLayers, CombatViewport)} ——
 *       模组层渲染插件（GraphicsLib 等）边界；</li>
 *   <li>{@code DecalRenderer.render(Sprite, float)} —— 舰船损伤 decal 的 stencil 区域边界；</li>
 *   <li>{@code WeaponDamageEffect#beginDamageRender(FFF)} —— 武器损伤渲染的 stencil 区域边界。</li>
 * </ul>
 * 引擎（EngineBatchImpl）、护盾（ShieldRenderHelper）、船体剪裁（ShipMaskMeshCache）
 * 边界由各自实现类内部直接调用，不经过本 Mixin。
 */
public final class SpriteBatchBoundaryMixins {

    private SpriteBatchBoundaryMixins() {
    }

    /**
     * @author GitHub Copilot
     * @reason 新舰船开始渲染前 flush 已累积的 sprite 批次，保持舰间绘制顺序。
     */
    @Mixin(targets = GameClassNames.SHIP_DOTTED)
    public abstract static class ShipBoundary {
        @Inject(
                method = "render(Lcom/fs/starfarer/api/combat/CombatEngineLayers;Lcom/fs/starfarer/combat/CombatViewport;)V",
                at = @At("HEAD"), remap = false)
        private void ssoptimizer$flushSpriteBatch(CallbackInfo ci) {
            SpriteBatch.getInstance().flushPending();
        }
    }

    /**
     * @author GitHub Copilot
     * @reason 模组层渲染插件（GraphicsLib 等）绘制前 flush，保持插件与舰船的层内顺序。
     */
    @Mixin(targets = GameClassNames.CUSTOM_COMBAT_ENTITY_DOTTED)
    public abstract static class CombatPluginBoundary {
        @Inject(
                method = "render(Lcom/fs/starfarer/api/combat/CombatEngineLayers;Lcom/fs/starfarer/combat/CombatViewport;)V",
                at = @At("HEAD"), remap = false)
        private void ssoptimizer$flushSpriteBatch(CallbackInfo ci) {
            SpriteBatch.getInstance().flushPending();
        }
    }

    /**
     * @author GitHub Copilot
     * @reason 损伤 decal 的 stencil 区域开始写掩码前 flush，避免船体批次延迟绘制盖住 decal。
     */
    @Mixin(targets = GameClassNames.DECAL_RENDERER_DOTTED)
    public abstract static class DecalBoundary {
        @Inject(method = "render(Lcom/fs/graphics/Sprite;F)V", at = @At("HEAD"), remap = false)
        private void ssoptimizer$flushSpriteBatch(CallbackInfo ci) {
            SpriteBatch.getInstance().flushPending();
        }
    }

    /**
     * @author GitHub Copilot
     * @reason 武器损伤渲染的 stencil 区域开始前 flush，保持武器炮管与损伤效果的顺序。
     */
    @Mixin(targets = GameClassNames.WEAPON_DAMAGE_EFFECT_DOTTED)
    public abstract static class WeaponDamageBoundary {
        @Inject(method = "beginDamageRender(FFF)V", at = @At("HEAD"), remap = false)
        private void ssoptimizer$flushSpriteBatch(CallbackInfo ci) {
            SpriteBatch.getInstance().flushPending();
        }
    }
}
