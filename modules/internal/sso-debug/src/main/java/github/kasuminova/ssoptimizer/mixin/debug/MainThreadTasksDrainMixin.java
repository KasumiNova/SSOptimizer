package github.kasuminova.ssoptimizer.mixin.debug;

import github.kasuminova.ssoptimizer.common.debug.MainThreadTasks;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 注入目标：{@code com.fs.starfarer.BaseGameState#traverse()} 内
 * {@code SoundManager.advance(FFFFFI)V} 调用点。<br>
 * 注入动机：调试脚本 {@code main} 线程模式需要与游戏主循环互斥的执行通道。
 * 帧循环结构：{@code AppDriver.begin} 只调一次（循环在其内部），
 * {@code traverse} 也只在状态切换间调一次（帧 while 循环在 traverse 内部），
 * 二者都不是每帧锚点；traverse 帧循环体内的 {@code SoundManager.advance} 调用
 * 每个循环迭代必达——包括窗口非聚焦空转分支（idle 跳过 render/advance 但仍在
 * 该循环内空转），调试任务因此在空转期间也能被排空。不选 lwjgl 锚点的原因：
 * RT 分支的 RenderThreadRedirect 会先把 traverse 里的 lwjgl 调用改写为 bridge
 * 调用，Mixin 匹配不到原始目标；SoundManager 是游戏自身类，两条分支形态一致。<br>
 * 注入效果：每循环迭代前排空主线程任务队列；队列空时仅为一次 poll，开销可忽略。
 */
@Mixin(targets = GameClassNames.BASE_GAME_STATE_DOTTED)
public abstract class MainThreadTasksDrainMixin {
    @Inject(method = "traverse", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/SoundManager;advance(FFFFFI)V"))
    private void ssoptimizer$drainMainThreadTasks(final CallbackInfoReturnable<String> cir) {
        MainThreadTasks.drain();
    }
}
