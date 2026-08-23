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

    /**
     * init 返回时标记资源加载期结束。
     * <p>
     * 注入动机：渲染线程分离模式下 RenderQueueImpl 的 StallDetector 熔断以此
     * 为门控边界——加载期推进画面本身就在渲染帧，纹理/字体/shader 的成批
     * 一次性分配产生的阻塞式 GL 调用属正常形态，不计入熔断窗口；加载结束后
     * 的稳态逐帧 getter 回读才是熔断目标。非分离模式下该标记无人读取，
     * 置位无副作用。
     */
    @Inject(method = "init", at = @At("RETURN"), remap = false)
    private void ssoptimizer$markLoadingFinished(CallbackInfo ci) {
        RenderThreadMode.markLoadingFinished();
        // 同一边界 flush Sound 构造器控制台输出的聚合零头（加载期音效已全部构造完毕）
        SoundMonoNoticeAggregator.flushPending();
    }
}
