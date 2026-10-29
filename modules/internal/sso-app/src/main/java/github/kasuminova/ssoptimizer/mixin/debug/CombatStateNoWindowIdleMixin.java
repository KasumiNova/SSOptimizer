package github.kasuminova.ssoptimizer.mixin.debug;

import com.fs.starfarer.settings.StarfarerSettings;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 取证用：禁用战斗状态「窗口非聚焦时帧循环空转暂停」行为的调试 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.CombatState#traverse()} 内
 * {@code StarfarerSettings.getBoolean(String)} 调用点。<br>
 * 注入动机：与 {@link BaseGameStateNoWindowIdleMixin} 相同，但 CombatState
 * 直接实现 {@code AppState}（不继承 BaseGameState），其帧循环内自带一份
 * {@code idleWhileWindowNotVisible} 空转分支（engine 暂停且窗口非激活时
 * sleep+continue），需要独立覆盖，否则无人值守的战斗自动化在窗口失焦时
 * 整体冻结。<br>
 * 注入效果：仅当 {@code -Dssoptimizer.debug.nowindowidle=true} 或
 * {@code -Dssoptimizer.debug.enabled=true} 时，
 * {@code idleWhileWindowNotVisible} 键恒返回 false（不进入空转分支）；
 * 其余键与未配置时完全透传原行为。
 */
@Mixin(targets = GameClassNames.COMBAT_STATE_DOTTED)
public abstract class CombatStateNoWindowIdleMixin {
    /** 调试开关：窗口非聚焦时不空转暂停（debug 会话默认开启）。 */
    private static final boolean NO_WINDOW_IDLE = Boolean.getBoolean("ssoptimizer.debug.nowindowidle")
            || Boolean.getBoolean("ssoptimizer.debug.enabled");

    @Redirect(method = "traverse", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/settings/StarfarerSettings;getBoolean(Ljava/lang/String;)Z"))
    private boolean ssoptimizer$noWindowIdle(final String key) {
        if (NO_WINDOW_IDLE && "idleWhileWindowNotVisible".equals(key)) {
            return false;
        }
        return StarfarerSettings.getBoolean(key);
    }
}
