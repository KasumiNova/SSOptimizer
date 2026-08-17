package github.kasuminova.ssoptimizer.mixin.ai;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TimeoutTrackerMap 的 items Map 并发化 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.api.util.TimeoutTrackerMap}<br>
 * 注入动机：AI 并行化后共享实例（如引擎级 customDataWithTimeout、模组共享 tracker）
 * 可能被多个工作线程并发 add/set/remove，原版 LinkedHashMap 并发写会损坏链表。<br>
 * 注入效果：构造返回点把 {@code items} 替换为 ConcurrentHashMap（保留初始内容）。
 * 已知语义变化：迭代顺序从插入序变为弱一致无序——{@code advance(float)} 的
 * 过期清理不依赖顺序，超时条目到期即删的语义不变。
 */
@Mixin(targets = GameClassNames.TIMEOUT_TRACKER_MAP_DOTTED)
public abstract class TimeoutTrackerMapConcurrentMixin {
    @Shadow(remap = false)
    private Map items;

    /**
     * @author KasumiNova
     * @reason items 并发化，兼容 AI 工作线程并发读写。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ssoptimizer$concurrentItems(CallbackInfo ci) {
        if (!(this.items instanceof ConcurrentHashMap)) {
            this.items = new ConcurrentHashMap<>(this.items);
        }
    }
}
