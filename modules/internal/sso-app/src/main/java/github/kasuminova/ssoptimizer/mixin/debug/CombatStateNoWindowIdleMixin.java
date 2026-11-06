package github.kasuminova.ssoptimizer.mixin.debug;

import com.fs.starfarer.settings.StarfarerSettings;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * CombatState 不继承 BaseGameState，需单独处理其 idleWhileWindowNotVisible 分支。
 */
@Mixin(targets = GameClassNames.COMBAT_STATE_DOTTED)
public abstract class CombatStateNoWindowIdleMixin {
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
