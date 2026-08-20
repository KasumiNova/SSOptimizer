package github.kasuminova.ssoptimizer.mixin.ai;

import com.fs.starfarer.combat.CollisionEntity;
import com.fs.starfarer.combat.ai.movement.maneuvers.EscortTargetManeuverV3;
import com.fs.starfarer.combat.entities.Ship;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * AIUtils.isEscortTargetOf 空引用守卫 Mixin（含并行窗口期 maneuver 失效守卫）。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.ai.AIUtils} 的私有静态递归方法
 * {@code isEscortTargetOf(Ship, Ship, int)}
 * <p>
 * 守卫一（arg 守卫，与既有无 null 参数修复同模式）：方法头判空，arg 或 arg2
 * 为 null 时返回 false——null 不可能是护航目标。公开版
 * {@code isEscortTargetOf(Ship, Ship)} 委托私有递归版，拦私有版一处即覆盖
 * 全部调用点（含递归自调用）。
 * <p>
 * 守卫二（maneuver 守卫，本次新增）：私有递归版内部
 * {@code if (var3.getCurrentManeuver() instanceof EscortTargetManeuverV3)}
 * 通过后，再执行 {@code var4 = (EscortTargetManeuverV3)var3.getCurrentManeuver()}
 * 取第二次引用——并行 AI 窗口期（worker 的 SpreadAI/FighterAI.advance 触达
 * 本方法时，该舰船的 maneuver 队列可能被同帧其他逻辑并发替换/清空），第二次
 * 调用可返回 null，随后 {@code var4.getTargetShip()} 直接 NPE（实机日志：
 * AIUtils.isEscortTargetOf 两处嵌套帧 → SpreadAI.advance → FighterAI.advance
 * → worker）。@Redirect 拦截 {@code EscortTargetManeuverV3.getTargetShip()}
 * 调用，receiver 为 null 时返回 null：后续 {@code null != arg2} 成立（arg2 由
 * 守卫一保证非 null）→ 递归 null target（守卫一兜底返回 false）→ 整体
 * return false——与「该 AI 当前 maneuver 已不是 EscortTarget」的语义一致
 * （null maneuver 视为非护航目标）。
 * <p>
 * 实现说明：Mixin 运行时校验禁止非 private 静态处理器，处理器必须为
 * private static；注入点正确性由单测以 ASM 解析真实游戏字节码验证。
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

    /**
     * @author KasumiNova
     * @reason instanceof 检查后第二次 getCurrentManeuver() 在并行窗口期可返回
     * null（maneuver 被并发替换），receiver 为 null 时 getTargetShip() 会 NPE；
     * 返回 null 使分支语义退化为「非护航目标」（递归 null 由守卫一兜底）。
     */
    @Redirect(
            method = "isEscortTargetOf(Lcom/fs/starfarer/combat/entities/Ship;Lcom/fs/starfarer/combat/entities/Ship;I)Z",
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/combat/ai/movement/maneuvers/EscortTargetManeuverV3;getTargetShip()Lcom/fs/starfarer/combat/CollisionEntity;"),
            remap = false)
    private static CollisionEntity ssoptimizer$guardEscortManeuverTarget(final EscortTargetManeuverV3 maneuver) {
        return maneuver != null ? maneuver.getTargetShip() : null;
    }
}
