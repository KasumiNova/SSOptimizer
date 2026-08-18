package github.kasuminova.ssoptimizer.mixin.debug;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 取证用：禁用「窗口非聚焦时主循环空转暂停」行为的调试 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.BaseGameState#traverse()} 内
 * {@code Display.isActive()} 调用点。<br>
 * 注入动机：主循环在 {@code idleWhileWindowNotVisible} 开启且窗口非激活时
 * sleep+continue 空转（不渲染、不 advance），自动化取证（无窗口焦点的
 * 无人值守运行）因此拿不到真实渲染帧。游戏内 settings.json 的
 * {@code idleWhileWindowNotVisible} 开关会被游戏回写且需逐目录配置，
 * 取证用途改为 JVM 属性控制更可靠。<br>
 * 注入效果：仅当 {@code -Dssoptimizer.debug.nowindowidle=true} 时，
 * 该调用点恒返回 true（视为窗口激活）；未配置时完全透传原行为。
 */
@Mixin(targets = GameClassNames.BASE_GAME_STATE_DOTTED)
public abstract class BaseGameStateNoWindowIdleMixin {
    /** 调试开关（{@code -Dssoptimizer.debug.nowindowidle=true}）：窗口非聚焦时不空转暂停。 */
    private static final boolean NO_WINDOW_IDLE = Boolean.getBoolean("ssoptimizer.debug.nowindowidle");

    @Redirect(method = "traverse", remap = false,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;isActive()Z"))
    private boolean ssoptimizer$noWindowIdle() {
        if (NO_WINDOW_IDLE) {
            return true;
        }
        return org.lwjgl.opengl.Display.isActive();
    }
}
