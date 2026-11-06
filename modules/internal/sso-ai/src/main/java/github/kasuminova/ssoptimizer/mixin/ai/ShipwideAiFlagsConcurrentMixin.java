package github.kasuminova.ssoptimizer.mixin.ai;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 将共享旗标替换为并发映射，支持 AI 工作线程并发读写。 */
@Mixin(targets = GameClassNames.SHIPWIDE_AI_FLAGS_DOTTED)
public abstract class ShipwideAiFlagsConcurrentMixin {
    @Shadow(remap = false)
    private Map flags;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ssoptimizer$concurrentFlags(CallbackInfo ci) {
        if (!(this.flags instanceof ConcurrentHashMap)) {
            this.flags = new ConcurrentHashMap<>(this.flags);
        }
    }
}
