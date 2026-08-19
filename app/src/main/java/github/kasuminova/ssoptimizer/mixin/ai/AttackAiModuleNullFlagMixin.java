package github.kasuminova.ssoptimizer.mixin.ai;

import github.kasuminova.ssoptimizer.common.combat.ai.AiThreadLocals;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * AttackAIModule 对 {@code AIUtils.null} 布尔开关访问的线程本地化 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.ai.attack.AttackAIModule}<br>
 * 注入动机：进攻模块在 {@code getAimPoint} 调用前后置位/复位 {@code AIUtils.null}
 * （该字段名字节码合法、Java 源码非法；本类只有 PUTSTATIC 写入、读取在 AIUtils 内部），
 * AI 并行后必须与 {@link AiUtilsThreadLocalMixin} 一样走线程本地状态，
 * 否则工作线程间互相覆盖。<br>
 * 注入效果：本类内对 {@code AIUtils.null} 的 PUTSTATIC 重定向到
 * {@link AiThreadLocals#setNullFlag}。
 */
@Mixin(targets = GameClassNames.ATTACK_AI_MODULE_DOTTED)
public abstract class AttackAiModuleNullFlagMixin {
    @Redirect(method = "*", remap = false, at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
            target = "Lcom/fs/starfarer/combat/ai/AIUtils;null:Z"))
    private static void ssoptimizer$setNullFlag(boolean value) {
        AiThreadLocals.setNullFlag(value);
    }
}
