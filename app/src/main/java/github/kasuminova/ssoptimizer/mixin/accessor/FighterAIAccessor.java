package github.kasuminova.ssoptimizer.mixin.accessor;

import com.fs.starfarer.combat.ai.FighterWing;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 战机 AI（FighterAI）的 Mixin Accessor。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.ai.FighterAI}<br>
 * 注入动机：并行 AI 调度需要按 {@code FighterWing} 分组——同编队战机共享 wing 状态，
 * 其 AI 任务必须串行；{@code wing} 为 private 字段且项目规范禁止反射。<br>
 * 注入效果：暴露 {@code wing} 字段只读访问。
 */
@Mixin(targets = GameClassNames.FIGHTER_AI_DOTTED)
public interface FighterAIAccessor {
    /**
     * @return 该战机所属编队（可为 null，如游离战机）
     */
    @Accessor(value = "wing", remap = false)
    FighterWing ssoptimizer$getWing();
}
