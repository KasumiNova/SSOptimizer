package github.kasuminova.ssoptimizer.mixin.bench;

import github.kasuminova.ssoptimizer.common.bench.BenchmarkDriver;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 原版与桥接的 Display.update 调用点按渲染模式互斥，因此两个帧尾注入均允许缺失。 */
@Mixin(targets = GameClassNames.COMBAT_STATE_DOTTED)
public abstract class CombatStateBenchmarkMixin {

    @Inject(method = "showWarroom", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$suppressWarroom(final CallbackInfo ci) {
        if (BenchmarkDriver.isEnabled()) {
            ci.cancel();
        }
    }

    @Inject(method = "showDeployDialog", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$suppressDeployDialog(final CallbackInfo ci) {
        if (BenchmarkDriver.isEnabled()) {
            ci.cancel();
        }
    }

    @Group(name = "combatFrameEnd", min = 1)
    @Inject(method = "traverse", remap = false, require = 0, expect = 0,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;update(Z)V"))
    private void ssoptimizer$onCombatFrameEnd(final CallbackInfoReturnable<String> cir) {
        BenchmarkDriver.onCombatFrameEnd();
    }

    @Group(name = "combatFrameEnd", min = 1)
    @Inject(method = "traverse", remap = false, require = 0, expect = 0,
            at = @At(value = "INVOKE",
                    target = "Lgithub/kasuminova/ssoptimizer/bridge/opengl/Display;update(Z)V"))
    private void ssoptimizer$onCombatFrameEndBridged(final CallbackInfoReturnable<String> cir) {
        BenchmarkDriver.onCombatFrameEnd();
    }
}
