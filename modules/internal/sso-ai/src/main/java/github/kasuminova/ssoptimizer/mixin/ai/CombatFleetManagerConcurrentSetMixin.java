package github.kasuminova.ssoptimizer.mixin.ai;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将 {@code deployed} 替换为弱一致并发集合，避免 AI 工作线程迭代时并发修改。
 * 该集合支持 {@code iterator.remove()}，但不保持插入顺序。
 */
@Mixin(targets = GameClassNames.COMBAT_FLEET_MANAGER_DOTTED)
public abstract class CombatFleetManagerConcurrentSetMixin {
    @Shadow(remap = false)
    private Set deployed;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ssoptimizer$concurrentDeployed(CallbackInfo ci) {
        if (!(this.deployed instanceof ConcurrentHashMap.KeySetView)) {
            Set concurrent = ConcurrentHashMap.newKeySet(this.deployed.size());
            concurrent.addAll(this.deployed);
            this.deployed = concurrent;
        }
    }
}
