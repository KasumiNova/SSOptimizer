package github.kasuminova.ssoptimizer.mixin.debug;

import github.kasuminova.ssoptimizer.common.bench.DebugFrameCapture;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 调试帧抓取的游戏状态 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.BaseGameState#traverse()} 内
 * {@code Display.update(true)} 调用点。<br>
 * 注入动机：bridge {@code Display} 的抓取钩子只在渲染线程流水线（rt 分支）下生效，
 * 主线尚未启用该流水线；而 {@code org.lwjgl} 在 NanoForge 启动期被声明为
 * classloader exclusion（NanoForgeLaunchHelper），无法被 Mixin 变换。
 * {@code BaseGameState} 是标题/战役等游戏状态的公共基类，其 traverse 每帧
 * 调用 {@code Display.update(true)}，是覆盖全部非战斗渲染路径的统一帧尾点位。<br>
 * 注入效果：每帧帧尾回调 {@link DebugFrameCapture#onDisplayUpdate()}（未配置
 * 系统属性时为空操作）。
 */
@Mixin(targets = GameClassNames.BASE_GAME_STATE_DOTTED)
public abstract class DisplayFrameCaptureMixin {
    @Inject(method = "traverse", remap = false,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;update(Z)V"))
    private void ssoptimizer$debugFrameCapture(final CallbackInfoReturnable<String> cir) {
        DebugFrameCapture.onDisplayUpdate();
    }
}
