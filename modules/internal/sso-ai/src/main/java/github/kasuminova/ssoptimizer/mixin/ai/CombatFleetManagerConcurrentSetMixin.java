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
 * CombatFleetManager 的 deployed Set 并发化 Mixin（CME 修复）。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.CombatFleetManager}<br>
 * 注入动机：长程测试实测 CME——AI 工作线程在 {@code BehaviorModule.advance} 中
 * for-each {@code getDeployed()} 返回的活跃 LinkedHashSet 本体，与并发写入
 * （部署/撤退/战沉移除）冲突。LinkedHashSet 的迭代器即底层 LinkedHashMap 的
 * LinkedKeyIterator，与堆栈完全吻合。<br>
 * 选型：不能用 CopyOnWriteArraySet——{@code removeDeployed} 依赖
 * {@code iterator.remove()}，COW 迭代器不支持。{@code ConcurrentHashMap.newKeySet()}
 * 迭代弱一致且 iterator.remove 受支持，是唯一满足约束的 JDK 内建并发 Set。<br>
 * 已知语义变化：迭代顺序从插入序变为弱一致无序。影响面评估：部署列表顺序仅影响
 * 旗舰选择平局打破与 UI 列表显示顺序，战斗逻辑无顺序依赖。<br>
 * 注入效果：构造返回点把 {@code deployed} 替换为并发 keySet（保留初始内容）。
 */
@Mixin(targets = GameClassNames.COMBAT_FLEET_MANAGER_DOTTED)
public abstract class CombatFleetManagerConcurrentSetMixin {
    @Shadow(remap = false)
    private Set deployed;

    /**
     * @author KasumiNova
     * @reason deployed 并发化，修复 AI 并行下 BehaviorModule 迭代该集合的 CME。
     */
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
