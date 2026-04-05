package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.starfarer.campaign.fleet.CampaignFleetMemberView;
import com.fs.starfarer.util.ColorShifter;
import com.fs.starfarer.util.ValueShifter;
import github.kasuminova.ssoptimizer.common.render.campaign.CampaignFleetPerformanceHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 战役舰队成员视图热点优化 Mixin。
 * <p>
 * 注入目标：{@link CampaignFleetMemberView#advance(float, com.fs.starfarer.campaign.fleet.CampaignFleetView)}<br>
 * 注入动机：原版会在每帧无条件推进多组 {@link ColorShifter} / {@link ValueShifter}，
 * 即便它们已经没有任何活动过渡；热点报告显示该方法在战役场景中占比较高。<br>
 * 注入效果：仅在视觉状态仍活跃或尚未完全回归基准值时才推进 shifter，减少空转更新。
 */
@Mixin(CampaignFleetMemberView.class)
public abstract class CampaignFleetMemberViewMixin {
    @Redirect(
            method = "advance",
            at = @At(value = "INVOKE", target = "Lcom/fs/starfarer/util/ColorShifter;advance(F)V"),
            remap = false)
    private void ssoptimizer$advanceColorShifterIfNeeded(final ColorShifter shifter,
                                                         final float amount) {
        CampaignFleetPerformanceHelper.advanceColorShifterIfNeeded(shifter, amount);
    }

    @Redirect(
            method = "advance",
            at = @At(value = "INVOKE", target = "Lcom/fs/starfarer/util/ValueShifter;advance(F)V"),
            remap = false)
    private void ssoptimizer$advanceValueShifterIfNeeded(final ValueShifter shifter,
                                                         final float amount) {
        CampaignFleetPerformanceHelper.advanceValueShifterIfNeeded(shifter, amount);
    }
}