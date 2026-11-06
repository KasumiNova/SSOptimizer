package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.common.render.CombatStateTraversalHook;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 渲染线程分离模式关闭时不存在 GL 重定向，traverse 内保留原始 LWJGL 的 glFinish 调用，
 * 本 Mixin 仅在该模式关闭时织入。模式开启时由 CombatStateTraversalMixin 处理 bridge 调用。
 */
@Mixin(targets = GameClassNames.COMBAT_STATE_DOTTED)
public abstract class CombatStateTraversalDirectMixin {
    @Redirect(
            method = "traverse()Ljava/lang/String;",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glFinish()V"),
            remap = false)
    private void ssoptimizer$callFinishIfEnabledDirect() {
        CombatStateTraversalHook.callFinishIfEnabled();
    }
}
