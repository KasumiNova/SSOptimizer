package github.kasuminova.ssoptimizer.mixin.ai;

import github.kasuminova.ssoptimizer.common.combat.ai.ParallelAiDispatcher;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版 Profiler 的 AI 工作线程守卫 Mixin。
 * <p>
 * 注入目标：{@code com.fs.profiler.Profiler}<br>
 * 注入动机：{@code begin(String)}/{@code end()} 在启用时操作静态采样栈，AI 工作线程
 * 触碰（如 {@code AIUtils.areHulksInTheWay} 内的 Profiler.begin）会 corrupt 主线程栈。<br>
 * 注入效果：两个方法 HEAD 守卫，工作线程直接 cancel；主线程行为不变。
 */
@Mixin(targets = GameClassNames.PROFILER_DOTTED)
public abstract class ProfilerWorkerGuardMixin {
    /**
     * @author KasumiNova
     * @reason AI 工作线程完全绕过原版采样，防止静态栈跨线程损坏。
     */
    @Inject(method = "begin(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ssoptimizer$skipBeginOnWorkerThread(String name, CallbackInfo ci) {
        if (ParallelAiDispatcher.isWorkerThread()) {
            ci.cancel();
        }
    }

    /**
     * @author KasumiNova
     * @reason AI 工作线程完全绕过原版采样，防止静态栈跨线程损坏。
     */
    @Inject(method = "end()V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ssoptimizer$skipEndOnWorkerThread(CallbackInfo ci) {
        if (ParallelAiDispatcher.isWorkerThread()) {
            ci.cancel();
        }
    }
}
