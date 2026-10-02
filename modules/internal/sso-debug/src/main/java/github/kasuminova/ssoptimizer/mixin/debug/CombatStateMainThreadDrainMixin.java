package github.kasuminova.ssoptimizer.mixin.debug;

import github.kasuminova.ssoptimizer.common.debug.MainThreadTasks;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 战斗状态帧循环的主线程调试任务排空锚点。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.CombatState#traverse()} 内全部
 * {@code SoundManager.advance(FFFFFI)V} 调用点。<br>
 * 注入动机：{@link MainThreadTasksDrainMixin} 的锚点在 {@code BaseGameState.traverse}，
 * 而 {@code CombatState} 直接实现 {@code AppState}（不继承 BaseGameState），
 * 进入战斗后 drain 锚点不再命中，{@code main} 线程模式的调试脚本在战斗中
 * 全部超时失败（实机现象：战斗中 script_invoke main 模式报 did not drain，
 * drainCount 冻结）。CombatState 帧循环内每迭代必达 SoundManager.advance
 * （主循环与固定步长路径各一处），语义与 BaseGameState 锚点一致。<br>
 * 注入效果：战斗帧循环每迭代前排空主线程任务队列；{@link MainThreadTasks#drain()}
 * 为幂等 poll，多处锚点重复调用无副作用。
 */
@Mixin(targets = GameClassNames.COMBAT_STATE_DOTTED)
public abstract class CombatStateMainThreadDrainMixin {
    /**
     * @author KasumiNova
     * @reason CombatState 不走 BaseGameState.traverse，战斗中 drain 锚点缺失。
     */
    @Inject(method = "traverse", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/SoundManager;advance(FFFFFI)V"))
    private void ssoptimizer$drainMainThreadTasks(final CallbackInfoReturnable<String> cir) {
        MainThreadTasks.drain();
    }
}
