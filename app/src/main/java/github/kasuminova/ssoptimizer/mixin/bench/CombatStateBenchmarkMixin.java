package github.kasuminova.ssoptimizer.mixin.bench;

import github.kasuminova.ssoptimizer.common.bench.BenchmarkDriver;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 基准测试的战斗状态 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.CombatState}<br>
 * 注入动机：基准测试需要 a) 抑制战斗开始时的部署对话框（{@code showDeployDialog} 会把引擎
 * 置为暂停并阻塞自动化；traverse 头部改写 {@code showDeploymentDialog} 字段无效——
 * {@code resetMemberVariables()} 在 traverse 开头将其重置为 true），b) 在每帧渲染完成、
 * swap buffers 之前驱动截图与采样计时。<br>
 * 注入效果：{@code showWarroom()}/{@code showDeployDialog()} 在基准模式下直接取消
 * （gl_benchmark 的插件自行从 reserves 生成舰船，不依赖部署流程）；
 * {@code Display.update} 调用点前回调 {@link BenchmarkDriver#onCombatFrameEnd()}。
 */
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

    /**
     * 帧尾回调（非分离模式锚点）：原版 {@code Display.update(Z)V} 调用点。
     * require=0——分离模式下该调用点已被 ASM 重定向到 bridge owner，由
     * {@link #ssoptimizer$onCombatFrameEndBridged} 命中。
     */
    @Inject(method = "traverse", remap = false, require = 0,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;update(Z)V"))
    private void ssoptimizer$onCombatFrameEnd(final CallbackInfoReturnable<String> cir) {
        BenchmarkDriver.onCombatFrameEnd();
    }

    /**
     * 帧尾回调（分离模式锚点）：调用点被 RenderThreadRedirectTransformer 改写为
     * bridge {@code Display.update(Z)V} 后由本 handler 命中，两种模式语义一致。
     */
    @Inject(method = "traverse", remap = false, require = 0,
            at = @At(value = "INVOKE",
                    target = "Lgithub/kasuminova/ssoptimizer/bridge/opengl/Display;update(Z)V"))
    private void ssoptimizer$onCombatFrameEndBridged(final CallbackInfoReturnable<String> cir) {
        BenchmarkDriver.onCombatFrameEnd();
    }
}
