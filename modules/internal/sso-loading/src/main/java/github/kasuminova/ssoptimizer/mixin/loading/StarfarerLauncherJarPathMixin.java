package github.kasuminova.ssoptimizer.mixin.loading;

import com.fs.starfarer.loading.scripts.ScriptStore;
import github.kasuminova.ssoptimizer.common.loading.CaseInsensitiveJarResolver;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(targets = GameMixinSignatures.StarfarerLauncher.TARGET_CLASS, remap = false)
public abstract class StarfarerLauncherJarPathMixin {
    @Redirect(
            method = GameMixinSignatures.StarfarerLauncher.LAUNCH_GAME,
            at = @At(value = "INVOKE", target = GameMixinSignatures.StarfarerLauncher.GET_JAR_FILES_TARGET),
            remap = false)
    private static List<String> ssoptimizer$resolvingJarFiles() {
        return CaseInsensitiveJarResolver.resolvingView(ScriptStore.getJarFiles());
    }
}
