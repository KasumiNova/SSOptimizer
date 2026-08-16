package github.kasuminova.ssoptimizer.mixin.combat;

import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatch;
import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatchStats;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 战斗渲染作用域 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.CombatEngine#render(Z)}<br>
 * 注入动机：Sprite 合批 P0 统计只在战斗渲染区间内计数（UI/标题/战役界面的
 * sprite 绘制不应污染样本）；{@code render(Z)} 是战斗帧渲染的唯一入口，
 * 其头尾即天然的战斗作用域边界。<br>
 * 注入效果：HEAD 开启 {@link SpriteBatchStats} 战斗作用域，RETURN 关闭并折叠一帧。
 * </p>
 */
@Mixin(targets = GameClassNames.COMBAT_ENGINE_DOTTED)
public abstract class CombatEngineScopeMixin {

    /**
     * @author GitHub Copilot
     * @reason 标记战斗渲染区间开始，供 Sprite 合批统计判定作用域。
     */
    @Inject(method = "render(Z)V", at = @At("HEAD"), remap = false)
    private void ssoptimizer$spriteBatchScopeBegin(boolean innerRender, CallbackInfo ci) {
        SpriteBatchStats.beginCombatScope();
    }

    /**
     * @author GitHub Copilot
     * @reason 标记战斗渲染区间结束，折叠当帧统计并按周期输出汇总。
     */
    @Inject(method = "render(Z)V", at = @At("RETURN"), remap = false)
    private void ssoptimizer$spriteBatchScopeEnd(boolean innerRender, CallbackInfo ci) {
        // 战斗帧渲染结束：flush 残余批次（必须在作用域关闭前，保持层末尾顺序）
        SpriteBatch.getInstance().flushPending();
        SpriteBatchStats.endCombatScope();
    }
}
