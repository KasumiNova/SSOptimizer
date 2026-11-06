package github.kasuminova.ssoptimizer.mixin.accessor;

import com.fs.starfarer.combat.ai.FighterWing;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 同编队战机共享编队状态，其 AI 任务必须串行执行。 */
@Mixin(targets = GameClassNames.FIGHTER_AI_DOTTED)
public interface FighterAIAccessor {
    @Accessor(value = "wing", remap = false)
    FighterWing ssoptimizer$getWing();
}
