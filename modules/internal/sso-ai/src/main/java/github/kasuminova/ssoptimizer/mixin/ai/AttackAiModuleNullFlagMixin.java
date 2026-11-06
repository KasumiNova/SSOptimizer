package github.kasuminova.ssoptimizer.mixin.ai;

import github.kasuminova.ssoptimizer.common.combat.ai.AiThreadLocals;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 将 {@code AttackAIModule} 对 {@code AIUtils.null} 的写入重定向到线程本地状态。 */
@Mixin(targets = GameClassNames.ATTACK_AI_MODULE_DOTTED)
public abstract class AttackAiModuleNullFlagMixin {
    @Redirect(method = "*", remap = false, at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
            target = "Lcom/fs/starfarer/combat/ai/AIUtils;null:Z"))
    private static void ssoptimizer$setNullFlag(boolean value) {
        AiThreadLocals.setNullFlag(value);
    }
}
