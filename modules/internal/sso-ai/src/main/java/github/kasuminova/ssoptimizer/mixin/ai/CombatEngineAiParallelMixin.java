package github.kasuminova.ssoptimizer.mixin.ai;

import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.ai.AI;
import com.fs.starfarer.util.InputEventList;
import github.kasuminova.ssoptimizer.common.combat.ai.AutofireBatchRunner;
import github.kasuminova.ssoptimizer.common.combat.ai.ParallelAiDispatcher;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 并行调度主 AI 循环，并在实体推进前等待任务完成及收集自动开火决策。
 * fast-time 段不在重定向切片内，保持串行。
 * {@code customData} 使用同步 {@link LinkedHashMap}，保留模组依赖的空键和空值语义。
 */
@Mixin(targets = GameClassNames.COMBAT_ENGINE_DOTTED)
public abstract class CombatEngineAiParallelMixin {
    @Shadow(remap = false)
    private Map<String, Object> customData;

    @Redirect(method = "advanceInner",
            at = @At(value = "INVOKE", target = "Lcom/fs/starfarer/combat/ai/AI;advance(F)V"),
            slice = @Slice(to = @At(value = "CONSTANT", args = "stringValue=Advancing entities")),
            remap = false)
    private void ssoptimizer$dispatchAiAdvance(AI ai, float amount) {
        ParallelAiDispatcher.dispatch(ai, amount);
    }

    @Inject(method = "advanceInner",
            at = @At(value = "CONSTANT", args = "stringValue=Advancing entities"),
            remap = false)
    private void ssoptimizer$awaitAiTasks(float amount, InputEventList inputEvents, CallbackInfo ci) {
        ParallelAiDispatcher.awaitAll();
        AutofireBatchRunner.collectAndCompute((CombatEngine) (Object) this, amount);
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ssoptimizer$concurrentCustomData(CallbackInfo ci) {
        this.customData = Collections.synchronizedMap(new LinkedHashMap<>(this.customData));
    }
}
