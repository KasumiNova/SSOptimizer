package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.common.render.CombatStateTraversalHook;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 渲染线程分离模式开启时，RenderThreadRedirectTransformer 先于 Mixin 把 traverse 内的
 * GL11.glFinish 改写为 bridge 调用，因此本 Mixin 以 bridge 的 GL11 为目标，仅在该模式开启时织入。
 * 模式关闭时由 CombatStateTraversalDirectMixin 处理原始 LWJGL 调用。
 */
@Mixin(targets = GameClassNames.COMBAT_STATE_DOTTED)
public abstract class CombatStateTraversalMixin {
    @Redirect(
            method = "traverse()Ljava/lang/String;",
            at = @At(value = "INVOKE", target = "Lgithub/kasuminova/ssoptimizer/bridge/opengl/GL11;glFinish()V"),
            remap = false)
    private void ssoptimizer$callFinishIfEnabled() {
        CombatStateTraversalHook.callFinishIfEnabled();
    }
}
