package github.kasuminova.ssoptimizer.mixin.ai;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Map;

/**
 * TimeoutTrackerMap 的 items Map 并发化 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.api.util.TimeoutTrackerMap}<br>
 * 注入动机：AI 并行化后共享实例（如引擎级 customDataWithTimeout、模组共享 tracker）
 * 可能被多个工作线程并发 add/set/remove，原版 LinkedHashMap 并发写会损坏链表。<br>
 * 注入效果：构造返回点把 {@code items} 包装为同步 Map（保留原 LinkedHashMap 实例与内容）。
 * 已知语义变化：包装为 {@link Collections#synchronizedMap} 而非 ConcurrentHashMap——
 * 原版 LinkedHashMap 允许 null key/value，模组可能依赖该行为，CHM 的 null 限制会让
 * 此类模组直接 NPE；迭代顺序保持插入序，{@code advance(float)} 过期清理语义不变。
 */
@Mixin(targets = GameClassNames.TIMEOUT_TRACKER_MAP_DOTTED)
public abstract class TimeoutTrackerMapConcurrentMixin {
    @Shadow(remap = false)
    private Map items;

    /**
     * @author KasumiNova
     * @reason items 并发化，兼容 AI 工作线程并发读写；同步包装保留 null 兼容性。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ssoptimizer$concurrentItems(CallbackInfo ci) {
        this.items = Collections.synchronizedMap(this.items);
    }
}
