package github.kasuminova.ssoptimizer.mixin.debug;

import github.kasuminova.ssoptimizer.common.debug.MainThreadTasks;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CombatState 不继承 BaseGameState，战斗中必须使用独立 drain 锚点。 */
@Mixin(targets = GameClassNames.COMBAT_STATE_DOTTED)
public abstract class CombatStateMainThreadDrainMixin {
    @Inject(method = "traverse", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/SoundManager;advance(FFFFFI)V"))
    private void ssoptimizer$drainMainThreadTasks(final CallbackInfoReturnable<String> cir) {
        MainThreadTasks.drain();
    }
}
