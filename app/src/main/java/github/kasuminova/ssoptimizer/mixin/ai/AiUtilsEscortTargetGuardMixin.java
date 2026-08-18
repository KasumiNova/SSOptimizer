package github.kasuminova.ssoptimizer.mixin.ai;

import com.fs.starfarer.combat.entities.Ship;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * AIUtils.isEscortTargetOf 空引用守卫 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.ai.AIUtils} 的私有静态递归方法
 * {@code isEscortTargetOf(Ship, Ship, int)}<br>
 * 注入动机：原版实现开头直接 {@code arg.getAI()}，递归内部又把
 * {@code EscortTargetManeuverV3.getTargetShip()} 的结果不作判空继续递归。
 * 原版单线程下 wing leader 死亡/护航目标销毁的状态变更在帧内是原子的；
 * 并行 AI 工作线程会读到中间态——{@code SpreadAI.advance} 传入的
 * {@code getWingIfFighter().getLeader()} 或递归中的 {@code getTargetShip()}
 * 可为 null，{@code arg.getAI()} 直接 NPE（实机日志已复现：
 * FighterAI → SpreadAI → isEscortTargetOf，并行派发器重抛崩游戏）。<br>
 * 注入效果：方法头判空，arg 或 arg2 为 null 时返回 false——null 不可能是
 * 护航目标，与原方法对非护航情形返回 false 的语义一致。公开版
 * {@code isEscortTargetOf(Ship, Ship)} 委托私有递归版，拦私有版一处即覆盖
 * 全部调用点（含递归自调用）。<br>
 * 实现说明：Mixin 运行时校验禁止非 private 静态处理器（InvalidMixinException），
 * 处理器必须为 private static；注入点正确性由单测以 ASM 解析真实游戏字节码验证。
 */
@Mixin(targets = GameClassNames.AI_UTILS_DOTTED)
public abstract class AiUtilsEscortTargetGuardMixin {
    /**
     * @author KasumiNova
     * @reason 并行 AI 下引擎状态非原子读，wing leader / 护航目标可为 null，
     * null 语义上不可能是护航目标，直接返回 false。
     */
    @Inject(
            method = "isEscortTargetOf(Lcom/fs/starfarer/combat/entities/Ship;Lcom/fs/starfarer/combat/entities/Ship;I)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void ssoptimizer$guardNullEscortArgs(final Ship arg, final Ship arg2, final int depth,
                                                        final CallbackInfoReturnable<Boolean> cir) {
        if (arg == null || arg2 == null) {
            cir.setReturnValue(false);
        }
    }
}
