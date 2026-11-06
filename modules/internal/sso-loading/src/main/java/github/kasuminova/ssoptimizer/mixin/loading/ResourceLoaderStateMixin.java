package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.logging.SoundMonoNoticeAggregator;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.locks.ReentrantLock;

@Mixin(targets = GameClassNames.RESOURCE_LOADER_STATE_DOTTED)
public abstract class ResourceLoaderStateMixin {
    @Unique
    private final ReentrantLock ssoptimizer$queueLock = new ReentrantLock();

    @Inject(method = "queueResource", at = @At("HEAD"), remap = false)
    private void ssoptimizer$lockQueue(CallbackInfo ci) {
        ssoptimizer$queueLock.lock();
    }

    @Inject(method = "queueResource", at = @At("RETURN"), remap = false)
    private void ssoptimizer$unlockQueue(CallbackInfo ci) {
        ssoptimizer$queueLock.unlock();
    }

    // 加载结束标记仅用于渲染线程分离模式的熔断门控，非分离模式下置位无副作用。
    @Inject(method = "init", at = @At("RETURN"), remap = false)
    private void ssoptimizer$markLoadingFinished(CallbackInfo ci) {
        RenderThreadMode.markLoadingFinished();
        SoundMonoNoticeAggregator.flushPending();
    }
}
