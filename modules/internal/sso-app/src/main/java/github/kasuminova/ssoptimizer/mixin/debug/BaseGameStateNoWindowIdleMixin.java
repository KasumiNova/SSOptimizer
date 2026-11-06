package github.kasuminova.ssoptimizer.mixin.debug;

import com.fs.starfarer.settings.StarfarerSettings;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 直接重定向 Display.isActive 会与渲染线程改写冲突，因此改为拦截游戏的 idleWhileWindowNotVisible 配置读取。
 */
@Mixin(targets = GameClassNames.BASE_GAME_STATE_DOTTED)
public abstract class BaseGameStateNoWindowIdleMixin {
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
