package github.kasuminova.ssoptimizer.mixin.debug;

import github.kasuminova.ssoptimizer.common.bench.DebugFrameCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 调试帧抓取的 Display Mixin。
 * <p>
 * 注入目标：{@code org.lwjgl.opengl.Display#update(boolean)}（LWJGL2 的
 * {@code update()} 内部委托 {@code update(true)}，单点即可覆盖全部帧尾）。<br>
 * 注入动机：bridge {@code Display} 的抓取钩子只在渲染线程流水线（rt 分支）下生效，
 * 主线尚未启用该流水线，bridge 类处于未接线状态；直接注入 LWJGL 的 Display
 * 可覆盖主菜单/加载/战斗等全部渲染路径。<br>
 * 注入效果：每帧帧尾回调 {@link DebugFrameCapture#onDisplayUpdate()}（未配置
 * 系统属性时为空操作）。
 */
@Mixin(targets = "org.lwjgl.opengl.Display", remap = false)
public abstract class DisplayFrameCaptureMixin {
    @Inject(method = "update(Z)V", at = @At("HEAD"), remap = false)
    private static void ssoptimizer$debugFrameCapture(final CallbackInfo ci) {
        DebugFrameCapture.onDisplayUpdate();
    }
}
