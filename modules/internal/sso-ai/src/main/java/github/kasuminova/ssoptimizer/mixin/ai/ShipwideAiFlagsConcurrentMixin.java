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
 * ShipwideAIFlags 的 flags Map 并发化 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.api.combat.ShipwideAIFlags}<br>
 * 注入动机：AI 并行化后，共享实例（舰队级旗标、模组共享旗标）可能被多个 AI 工作线程
 * 并发 set/unset，原版 HashMap 并发写会损坏内部结构。<br>
 * 注入效果：构造返回点把 {@code flags} 替换为 ConcurrentHashMap（保留初始内容）。
 * 字段声明类型为 Map，无需改签名。
 */
@Mixin(targets = GameClassNames.SHIPWIDE_AI_FLAGS_DOTTED)
public abstract class ShipwideAiFlagsConcurrentMixin {
    @Shadow(remap = false)
    private Map flags;

    /**
     * @author KasumiNova
     * @reason flags 并发化，兼容 AI 工作线程并发读写。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ssoptimizer$concurrentFlags(CallbackInfo ci) {
        if (!(this.flags instanceof ConcurrentHashMap)) {
            this.flags = new ConcurrentHashMap<>(this.flags);
        }
    }
}
