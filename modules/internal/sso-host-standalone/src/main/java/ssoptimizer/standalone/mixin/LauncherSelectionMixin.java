package ssoptimizer.standalone.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ssoptimizer.standalone.game.ModSelection;

import java.util.List;

@Mixin(targets = "com.fs.starfarer.StarfarerLauncher", remap = false)
public abstract class LauncherSelectionMixin {
    @ModifyExpressionValue(method = "launchGame(ZZLjava/lang/String;Ljava/lang/String;)V",
            at = @At(value = "INVOKE", target = "Lcom/fs/starfarer/launcher/ModManager;getEnabledMods()Ljava/util/List;"))
    private static List<?> ssoptimizer$verifyModSelection(List<?> selected) {
        ModSelection.verify(selected);
        return selected;
    }
}
