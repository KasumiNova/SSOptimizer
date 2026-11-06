package github.kasuminova.ssoptimizer.mixin.debug;

import github.kasuminova.ssoptimizer.common.debug.MainThreadTasks;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** SoundManager.advance 覆盖空转分支，InputState.getEvents 覆盖正常渲染分支，两者均不受 RenderThreadRedirect 的 LWJGL 调用改写影响。 */
@Mixin(targets = GameClassNames.BASE_GAME_STATE_DOTTED)
public abstract class MainThreadTasksDrainMixin {
    @Inject(method = "traverse", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/SoundManager;advance(FFFFFI)V"))
    private void ssoptimizer$drainMainThreadTasks(final CallbackInfoReturnable<String> cir) {
        MainThreadTasks.drain();
    }

    @Inject(method = "traverse", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/util/InputState;getEvents()Lcom/fs/starfarer/util/InputEventList;"))
    private void ssoptimizer$drainMainThreadTasksNormalPath(final CallbackInfoReturnable<String> cir) {
        MainThreadTasks.drain();
    }
}
