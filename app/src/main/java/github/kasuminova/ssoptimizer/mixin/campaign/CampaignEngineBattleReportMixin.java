package github.kasuminova.ssoptimizer.mixin.campaign;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * 修复战役引擎在报告战斗事件时的 {@code ConcurrentModificationException}。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.CampaignEngine#reportBattleOccurred(CampaignFleetAPI, BattleAPI)}<br>
 * 注入动机：{@code CampaignFleet.getEventListeners()} 直接返回内部 {@code despawnListeners}
 * 字段引用（无防御性副本），当 {@code FleetEventListener.reportBattleOccurred()} 回调
 * 在迭代期间增删监听器时，会触发 {@code ConcurrentModificationException}。<br>
 * 注入效果：将 {@code getEventListeners()} 返回值替换为防御性副本，避免迭代期间列表被修改。
 */
@Mixin(targets = GameMixinSignatures.CampaignEngine.TARGET_CLASS)
public abstract class CampaignEngineBattleReportMixin {

    /**
     * 将战斗事件报告中 {@code fleet.getEventListeners()} 调用替换为返回防御性副本，
     * 防止监听器回调在迭代期间修改列表导致 CME。
     *
     * @param fleet 被查询事件监听器的舰队
     * @return 事件监听器列表的浅拷贝
     */
    @Redirect(
            method = GameMixinSignatures.CampaignEngine.REPORT_BATTLE_OCCURRED,
            at = @At(value = "INVOKE",
                     target = GameMixinSignatures.CampaignEngine.GET_EVENT_LISTENERS_TARGET),
            remap = false)
    private List<?> ssoptimizer$copyFleetEventListeners(final CampaignFleetAPI fleet) {
        return new ArrayList<>(fleet.getEventListeners());
    }
}
