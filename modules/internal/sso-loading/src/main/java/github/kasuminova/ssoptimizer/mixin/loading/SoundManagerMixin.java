package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.loading.sound.ParallelSoundLoadCoordinator;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = GameMixinSignatures.SoundManager.TARGET_CLASS)
public abstract class SoundManagerMixin {
    @Inject(method = GameMixinSignatures.SoundManager.LOAD_OBJECT_FAMILY, at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$reuseObjectFamilyBytes(final String resourcePath,
                                                    final CallbackInfoReturnable<Object> cir) {
        completeIfLoaded(cir, ParallelSoundLoadCoordinator.loadObjectFamily(this, resourcePath));
    }

    @Inject(method = GameMixinSignatures.SoundManager.LOAD_O00000_FAMILY, at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$reuseO00000FamilyBytes(final String resourcePath,
                                                    final CallbackInfoReturnable<Object> cir) {
        completeIfLoaded(cir, ParallelSoundLoadCoordinator.loadO00000Family(this, resourcePath));
    }

    @Inject(method = GameMixinSignatures.SoundManager.LOAD_O_ACCENT_FAMILY, at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$reuseOAccentFamilyBytes(final String resourcePath,
                                                     final CallbackInfoReturnable<Object> cir) {
        completeIfLoaded(cir, ParallelSoundLoadCoordinator.loadOAccentFamily(this, resourcePath));
    }

    private static void completeIfLoaded(final CallbackInfoReturnable<Object> cir,
                                         final Object loaded) {
        if (loaded != null) {
            cir.setReturnValue(loaded);
        }
    }
}
