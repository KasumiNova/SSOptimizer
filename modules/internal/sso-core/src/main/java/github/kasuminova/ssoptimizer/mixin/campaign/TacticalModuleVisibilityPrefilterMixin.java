package github.kasuminova.ssoptimizer.mixin.campaign;

import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.campaign.fleet.CampaignFleet;
import com.fs.starfarer.settings.StarfarerSettings;
import com.fs.util.container.repo.ObjectRepository;
import github.kasuminova.ssoptimizer.common.campaign.TacticalVisibilityPrefilter;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * 战术模块舰队扫描距离预过滤 Mixin（B5，默认关）。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.ai.TacticalModule#advance(float)}
 * 内 {@code ObjectRepository.getList(CampaignFleet.class)} 调用点
 * （interval 内「Looking at other fleets」扫描的舰队列表来源；
 * 方法内唯一一处 {@code getList} 调用，另一处同名调用在 {@code hasEnoughStuffAround}）<br>
 * 注入动机：interval 内每个 AI 舰队都对 location 全舰队做
 * {@code getVisibilityLevelTo} 精判，总体 O(F²)，密集舰队星域下是
 * {@code TacticalModule.advance} 热点。<br>
 * 注入效果：{@code -Dssoptimizer.campaign.tacticalPrefilter=true}（默认 false=关闭）时，
 * 把「精判必然返回 NONE」的舰队从扫描列表中剔除（等价性论证与距离无关出口的兜底见
 * {@link TacticalVisibilityPrefilter} 类注释）；关闭时原样返回原版 live 列表，零行为差异。<br>
 * 行为差异边界（开启时）：精判为纯查询无副作用、循环体对 NONE 舰队无动作，
 * 故开启后与原版可观察行为一致；唯一敞口是「传感器全局关闭 + 观察方为玩家舰队」
 * 与「目标无 sensor profile」两个距离无关出口——已在下方过滤前显式兜底排除。
 */
@Mixin(targets = GameMixinSignatures.TacticalModule.TARGET_CLASS, remap = false)
public abstract class TacticalModuleVisibilityPrefilterMixin {
    @Shadow
    private CampaignFleet fleet;

    /**
     * 用距离预过滤后的列表替换 interval 扫描列表。
     *
     * @param objects location 实体仓库（原版调用接收者）
     * @param type    原版调用参数（{@code CampaignFleet.class}）
     * @return 预过滤后的舰队列表；开关关闭时返回原版 live 列表
     */
    @Redirect(
            method = GameMixinSignatures.TacticalModule.ADVANCE,
            at = @At(value = "INVOKE", target = GameMixinSignatures.TacticalModule.GET_LIST_TARGET),
            remap = false)
    @SuppressWarnings("unchecked")
    private List<?> ssoptimizer$prefilterScannedFleets(final ObjectRepository objects, final Class<?> type) {
        final List<CampaignFleet> fleets = objects.getList(CampaignFleet.class);
        if (!TacticalVisibilityPrefilter.isEnabled()) {
            return fleets;
        }

        // 距离无关出口兜底一：战役传感器全局关闭且观察方为玩家舰队时，
        // 精判对无 "ghost" 标签的舰队无视距离返回最高可见级，不能过滤
        if (this.fleet.isPlayerFleet() && !StarfarerSettings.isCampaignSensorsOn()) {
            return fleets;
        }

        final float viewerX = this.fleet.getLocation().x;
        final float viewerY = this.fleet.getLocation().y;
        final float viewerRadius = this.fleet.getRadius();
        final float sensorRangeMax =
                StarfarerSettings.getSensorRangeMaxForLocation(this.fleet.getContainingLocation());

        final List<CampaignFleet> filtered = new ArrayList<>(fleets.size());
        for (final CampaignFleet target : fleets) {
            // 距离无关出口兜底二：无 sensor profile 的舰队精判无视距离返回最高可见级
            if (!target.hasSensorProfile()) {
                filtered.add(target);
                continue;
            }
            final float centerDist = Misc.getDistance(
                    viewerX, viewerY, target.getLocation().x, target.getLocation().y);
            if (!TacticalVisibilityPrefilter.isDefinitelyInvisible(
                    centerDist, target.getRadius(), viewerRadius,
                    sensorRangeMax, target.getExtendedDetectedAtRange())) {
                filtered.add(target);
            }
        }
        return filtered;
    }
}
