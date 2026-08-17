package github.kasuminova.ssoptimizer.mixin.ai;

import com.fs.starfarer.combat.ai.AI;
import com.fs.starfarer.util.InputEventList;
import github.kasuminova.ssoptimizer.common.combat.ai.ParallelAiDispatcher;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战斗引擎 AI 循环并行化 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.CombatEngine}<br>
 * 注入动机：{@code advanceInner} 内实体 AI 串行执行是大规模战场 advance 阶段的主要开销。<br>
 * 注入效果：
 * <ol>
 *   <li>AI 循环内的 {@code AI.advance(F)V} 调用（slice 限定在 ldc "Advancing entities"
 *       之前，即仅主循环；fast-time 段的第二处调用不受影响保持串行）重定向到
 *       {@link ParallelAiDispatcher#dispatch}；</li>
 *   <li>循环结束（ldc "Advancing entities" 之前）注入
 *       {@link ParallelAiDispatcher#awaitAll} 帧内屏障；</li>
 *   <li>{@code customData} 字段写入重定向为 ConcurrentHashMap 包装
 *       （AI 并行后模组/AI 代码可能在工作线程读写该 Map）。</li>
 * </ol>
 */
@Mixin(targets = GameClassNames.COMBAT_ENGINE_DOTTED)
public abstract class CombatEngineAiParallelMixin {
    @Shadow(remap = false)
    private Map<String, Object> customData;

    /**
     * @author KasumiNova
     * @reason AI 循环内首个 advance 调用点（主循环）改为并行调度；fast-time 段调用点在
     * slice 边界（ldc "Advancing entities"）之后，不受影响。
     */
    @Redirect(method = "advanceInner",
            at = @At(value = "INVOKE", target = "Lcom/fs/starfarer/combat/ai/AI;advance(F)V"),
            slice = @Slice(to = @At(value = "CONSTANT", args = "stringValue=Advancing entities")),
            remap = false)
    private void ssoptimizer$dispatchAiAdvance(AI ai, float amount) {
        ParallelAiDispatcher.dispatch(ai, amount);
    }

    /**
     * @author KasumiNova
     * @reason AI 主循环结束、实体 advance 段（"Advancing entities"）开始前插入帧内屏障，
     * 保证全部并行 AI 任务完成后才继续。
     */
    @Inject(method = "advanceInner",
            at = @At(value = "CONSTANT", args = "stringValue=Advancing entities"),
            remap = false)
    private void ssoptimizer$awaitAiTasks(float amount, InputEventList inputEvents, CallbackInfo ci) {
        ParallelAiDispatcher.awaitAll();
    }

    /**
     * @author KasumiNova
     * @reason customData 在 AI 并行后可能被工作线程并发读写，构造返回点统一替换为
     * ConcurrentHashMap（两处 HashMap 初始化均在 <init> 内，字节码已核实）。
     */
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ssoptimizer$concurrentCustomData(CallbackInfo ci) {
        if (!(this.customData instanceof ConcurrentHashMap)) {
            this.customData = new ConcurrentHashMap<>(this.customData);
        }
    }
}
