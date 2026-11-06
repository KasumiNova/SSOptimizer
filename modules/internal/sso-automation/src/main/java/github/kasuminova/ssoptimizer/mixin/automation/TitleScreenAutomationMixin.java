package github.kasuminova.ssoptimizer.mixin.automation;

import com.fs.starfarer.title.MissionWidget;
import com.fs.starfarer.title.TitleScreenState;
import github.kasuminova.ssoptimizer.common.automation.AutomationMissionLauncher;
import github.kasuminova.ssoptimizer.common.automation.SaveLoadCycleDriver;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(targets = GameClassNames.TITLE_SCREEN_STATE_DOTTED)
public abstract class TitleScreenAutomationMixin {
    @Shadow
    private Map session;

    @Shadow
    private MissionWidget missionWidget;

    @Inject(method = "advance", at = @At("RETURN"))
    private void ssoptimizer$launchAutomationMission(final CallbackInfo ci) {
        if (SaveLoadCycleDriver.SCENARIO.equals(
                System.getProperty(github.kasuminova.ssoptimizer.common.automation.AutomationConfig.SCENARIO_PROPERTY, ""))) {
            SaveLoadCycleDriver.tryAdvance(this.session);
            return;
        }
        AutomationMissionLauncher.tryLaunchFromTitleScreen((TitleScreenState) (Object) this, this.missionWidget);
    }
}