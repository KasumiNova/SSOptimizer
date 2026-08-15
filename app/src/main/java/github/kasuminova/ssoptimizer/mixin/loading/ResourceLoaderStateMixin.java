package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.locks.ReentrantLock;

/**
 * ResourceLoaderState 加载路径的 Mixin 注入。
 * <p>
 * 注入目标：{@code com.fs.starfarer.loading.ResourceLoaderState}<br>
 * 注入动机：并行 Spec 加载（{@link SpecStoreMixin}）下，多个加载任务会并发调用
 * {@code queueResource} 注册待加载资源，而原版的 {@code resources} 是普通
 * {@code ArrayList}、{@code totalWeight} 是普通 int，并发写会损坏队列。<br>
 * 注入效果：在 {@code queueResource} 进入/返回时分别加锁/解锁一把可重入锁，
 * 串行化整个方法体，语义与原版完全一致（方法体不抛出受检异常，无死锁路径）。
 */
@Mixin(targets = GameClassNames.RESOURCE_LOADER_STATE_DOTTED)
public abstract class ResourceLoaderStateMixin {
    @Unique
    private final ReentrantLock ssoptimizer$queueLock = new ReentrantLock();

    /**
     * queueResource 进入时加锁。
     */
    @Inject(method = "queueResource", at = @At("HEAD"), remap = false)
    private void ssoptimizer$lockQueue(CallbackInfo ci) {
        ssoptimizer$queueLock.lock();
    }

    /**
     * queueResource 返回时解锁（覆盖全部 RETURN 路径）。
     */
    @Inject(method = "queueResource", at = @At("RETURN"), remap = false)
    private void ssoptimizer$unlockQueue(CallbackInfo ci) {
        ssoptimizer$queueLock.unlock();
    }
}
