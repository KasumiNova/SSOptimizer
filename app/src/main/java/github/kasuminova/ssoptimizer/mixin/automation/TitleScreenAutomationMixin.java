package github.kasuminova.ssoptimizer.mixin.automation;

import github.kasuminova.ssoptimizer.common.automation.AutomationMissionLauncher;
import github.kasuminova.ssoptimizer.common.automation.SaveLoadCycleDriver;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * 注入目标：{@code com.fs.starfarer.title.TitleScreenState#advance(float, InputState)}。<br>
 * 注入动机：自动化烟测需要在游戏主菜单稳定后进入指定 mission 或自动读档。标题界面持有普通 mission 列表，
 * 在 advance 尾部查找并调用公开的 {@code missionAccepted(...)} 入口可以复用原版 mission 加载流程；
 * 读档场景则直接复刻读档对话框确认分支调用 {@code CampaignGameManager.loadGame}。<br>
 * 注入效果：当 {@code ssoptimizer.automation.enabled=true} 时按 scenario 自动执行对应流程。
 */
@Mixin(targets = GameClassNames.TITLE_SCREEN_STATE_DOTTED)
public abstract class TitleScreenAutomationMixin {
    @Shadow
    private Map session;

    @Inject(method = "advance", at = @At("RETURN"))
    private void ssoptimizer$launchAutomationMission(final CallbackInfo ci) {
        // scenario 分发：读档场景与 mission 场景互斥，避免 mission 启动器误把
        // scenario 名当 mission id 选中并进入战斗
        if (SaveLoadCycleDriver.SCENARIO.equals(
                System.getProperty(github.kasuminova.ssoptimizer.common.automation.AutomationConfig.SCENARIO_PROPERTY, ""))) {
            SaveLoadCycleDriver.tryAdvance(this.session);
            return;
        }
        AutomationMissionLauncher.tryLaunchFromTitleScreen(this);
    }
}