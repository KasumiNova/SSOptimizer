package ssoptimizer.standalone.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ssoptimizer.standalone.game.ModSelection;

@Mixin(targets = "com.fs.starfarer.launcher.opengl.GLLauncher", remap = false)
public abstract class GlLauncherSelectionMixin {
    @Inject(method = "actionPerformed(Ljava/lang/Object;Ljava/lang/Object;)V", cancellable = true,
            at = @At(value = "FIELD", target = "Lcom/fs/starfarer/launcher/opengl/GLLauncher;isDevMode:Z", ordinal = 0))
    private void ssoptimizer$keepLauncherOpen(CallbackInfo callback) {
        if (!ModSelection.beforeClosingLauncher()) callback.cancel();
    }
}
