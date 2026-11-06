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
 * 使用同步映射包装共享超时表，保留插入顺序及模组依赖的空键和空值语义。
 */
@Mixin(targets = GameClassNames.TIMEOUT_TRACKER_MAP_DOTTED)
public abstract class TimeoutTrackerMapConcurrentMixin {
    @Shadow(remap = false)
    private Map items;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ssoptimizer$concurrentItems(CallbackInfo ci) {
        this.items = Collections.synchronizedMap(this.items);
    }
}
