package ssoptimizer.standalone.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ssoptimizer.standalone.game.ModSelection;

import java.util.List;

@Mixin(targets = "com.fs.starfarer.StarfarerLauncher", remap = false)
public abstract class LauncherSelectionMixin {
    @ModifyExpressionValue(method = "<init>(Z)V",
            at = @At(value = "INVOKE", target = "Lcom/fs/starfarer/settings/StarfarerSettings;isLaunchDirect()Z"))
    private boolean ssoptimizer$allowDirectLaunch(boolean direct) {
        return direct && ModSelection.canLaunchDirectly();
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void ssoptimizer$keepLauncherOpen(CallbackInfo callback) {
        if (!ModSelection.beforeClosingLauncher()) callback.cancel();
    }

    @ModifyExpressionValue(method = "launchGame(ZZLjava/lang/String;Ljava/lang/String;)V",
            at = @At(value = "INVOKE", target = "Lcom/fs/starfarer/launcher/ModManager;getEnabledMods()Ljava/util/List;"))
    private static List<?> ssoptimizer$restartIfChanged(List<?> selected) {
        ModSelection.restartIfChanged(selected);
        return selected;
    }
}
