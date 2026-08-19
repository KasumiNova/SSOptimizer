package github.kasuminova.ssoptimizer.mixin.bench;

import github.kasuminova.ssoptimizer.common.bench.BenchmarkDriver;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 基准测试的标题界面 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.title.TitleScreenState#advance(float, InputState)}<br>
 * 注入动机：基准测试需要在主菜单稳定后自动进入指定 mission（默认 gl_benchmark）。<br>
 * 注入效果：advance 尾部调用 {@link BenchmarkDriver#tryLaunchFromTitleScreen(Object)}，
 * 命中 mission 后复用原版 {@code missionAccepted} 流程进入战斗。
 */
@Mixin(targets = GameClassNames.TITLE_SCREEN_STATE_DOTTED)
public abstract class TitleScreenBenchmarkMixin {
    @Inject(method = "advance", at = @At("RETURN"), remap = false)
    private void ssoptimizer$launchBenchmarkMission(final CallbackInfo ci) {
        BenchmarkDriver.tryLaunchFromTitleScreen(this);
    }
}
