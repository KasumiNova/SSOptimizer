package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.campaign.fleet.CampaignFleetView;
import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import github.kasuminova.ssoptimizer.common.render.campaign.CampaignFleetPerformanceHelper;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 战役舰队视图尾迹热点优化 Mixin。
 * <p>
 * 注入目标：{@link CampaignFleetView#advance(float)} 与 {@link CampaignFleetView#renderContrails(float)}<br>
 * 注入动机：原版只要尾迹引擎实例存在，就会每帧调用 {@link ContrailEngineV2#advance(float)} /
 * {@link ContrailEngineV2#render(float)}，即便内部尾迹集合为空；热点报告显示这部分存在稳定开销。
 * 此外原版对视口外舰队的尾迹照常执行逐点渲染。<br>
 * 注入效果：仅在尾迹集合非空时推进或渲染尾迹，避免空集合路径上的分配与遍历；
 * 渲染入口额外按舰队位置做视口距离 LOD（超
 * {@link CampaignFleetPerformanceHelper#CONTRAIL_LOD_MARGIN} 边距整条跳过），
 * 尾迹推进不做距离裁剪，保证老化/清理语义与原版一致。
 */
@Mixin(CampaignFleetView.class)
public abstract class CampaignFleetViewMixin {
    @Redirect(
            method = "advance",
            at = @At(value = "INVOKE", target = GameMixinSignatures.CampaignFleetView.CONTRAIL_ADVANCE_TARGET),
            remap = false)
    private void ssoptimizer$advanceContrailsIfNeeded(final ContrailEngineV2 contrails,
                                                      final float amount) {
        CampaignFleetPerformanceHelper.advanceContrailsIfNeeded(contrails, amount);
    }

    @Redirect(
            method = "renderContrails",
            at = @At(value = "INVOKE", target = GameMixinSignatures.CampaignFleetView.CONTRAIL_RENDER_TARGET),
            remap = false)
    private void ssoptimizer$renderContrailsIfNeeded(final ContrailEngineV2 contrails,
                                                     final float alphaMult) {
        final CampaignFleetView self = (CampaignFleetView) (Object) this;
        CampaignFleetPerformanceHelper.renderContrailsIfNeeded(
                contrails, alphaMult,
                self.getFleet().getLocation(),
                Global.getSector().getViewport());
    }
}