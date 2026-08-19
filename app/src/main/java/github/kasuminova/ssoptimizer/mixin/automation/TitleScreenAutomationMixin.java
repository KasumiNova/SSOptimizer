package github.kasuminova.ssoptimizer.mixin.automation;

import github.kasuminova.ssoptimizer.common.automation.AutomationMissionLauncher;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入目标：{@code com.fs.starfarer.title.TitleScreenState#advance(float, InputState)}。<br>
 * 注入动机：自动化烟测需要在游戏主菜单稳定后进入指定 mission。标题界面持有普通 mission 列表，
 * 在 advance 尾部查找并调用公开的 {@code missionAccepted(...)} 入口可以复用原版 mission 加载流程。<br>
 * 注入效果：当 {@code ssoptimizer.automation.enabled=true} 时自动启动配置的 mission。
 */
@Mixin(targets = GameClassNames.TITLE_SCREEN_STATE_DOTTED)
public abstract class TitleScreenAutomationMixin {
    @Inject(method = "advance", at = @At("RETURN"))
    private void ssoptimizer$launchAutomationMission(final CallbackInfo ci) {
        AutomationMissionLauncher.tryLaunchFromTitleScreen(this);
    }
}