package github.kasuminova.ssoptimizer.mixin.bench;

import github.kasuminova.ssoptimizer.common.bench.BenchmarkDriver;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = GameClassNames.TITLE_SCREEN_STATE_DOTTED)
public abstract class TitleScreenBenchmarkMixin {
    @Inject(method = "advance", at = @At("RETURN"), remap = false)
    private void ssoptimizer$launchBenchmarkMission(final CallbackInfo ci) {
        BenchmarkDriver.tryLaunchFromTitleScreen(this);
    }
}
