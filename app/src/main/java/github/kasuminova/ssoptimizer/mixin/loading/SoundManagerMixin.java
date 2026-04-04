package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.loading.sound.ParallelSoundLoadCoordinator;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 声音管理器路径加载入口的 Mixin 注入。
 * <p>
 * 注入目标：{@code sound.SoundManager}<br>
 * 注入动机：通过 mapping 将原始混淆类 {@code sound.Object} 及其三个路径加载方法重命名后，
 * 这些入口已经可以稳定使用 Mixin 头部注入接管，不再需要整段方法替换式 ASM。<br>
 * 注入效果：当预读缓存命中时，三个路径加载家族直接走
 * {@link ParallelSoundLoadCoordinator} 的内存字节路径；否则继续执行原始声音加载逻辑。
 */
@Mixin(targets = GameClassNames.SOUND_MANAGER_DOTTED)
public abstract class SoundManagerMixin {
    @Inject(method = "loadObjectFamily(Ljava/lang/String;)Lsound/O0OO;", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$reuseObjectFamilyBytes(final String resourcePath,
                                                    final CallbackInfoReturnable<Object> cir) {
        completeIfLoaded(cir, ParallelSoundLoadCoordinator.loadObjectFamily(this, resourcePath));
    }

    @Inject(method = "loadO00000Family(Ljava/lang/String;)Lsound/O0OO;", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$reuseO00000FamilyBytes(final String resourcePath,
                                                    final CallbackInfoReturnable<Object> cir) {
        completeIfLoaded(cir, ParallelSoundLoadCoordinator.loadO00000Family(this, resourcePath));
    }

    @Inject(method = "loadOAccentFamily(Ljava/lang/String;)Lsound/O0OO;", at = @At("HEAD"), cancellable = true, remap = false)
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
