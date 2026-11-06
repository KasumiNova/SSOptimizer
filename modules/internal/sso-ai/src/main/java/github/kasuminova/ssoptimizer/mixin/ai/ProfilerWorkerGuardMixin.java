package github.kasuminova.ssoptimizer.mixin.ai;

import github.kasuminova.ssoptimizer.common.combat.ai.ParallelAiDispatcher;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** AI 工作线程绕过原版采样，避免其静态采样栈与主线程互相破坏。 */
@Mixin(targets = GameClassNames.PROFILER_DOTTED)
public abstract class ProfilerWorkerGuardMixin {
    @Inject(method = "begin(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ssoptimizer$skipBeginOnWorkerThread(String name, CallbackInfo ci) {
        if (ParallelAiDispatcher.isWorkerThread()) {
            ci.cancel();
        }
    }

    @Inject(method = "end()V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ssoptimizer$skipEndOnWorkerThread(CallbackInfo ci) {
        if (ParallelAiDispatcher.isWorkerThread()) {
            ci.cancel();
        }
    }
}
