package github.kasuminova.ssoptimizer.mixin.econ;

import com.fs.starfarer.campaign.econ.CommodityOnMarket;
import github.kasuminova.ssoptimizer.common.campaign.econ.CommodityEventModRefreshBridge;
import github.kasuminova.ssoptimizer.common.campaign.econ.CommodityEventModRefreshHelper;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 市场推进阶段的商品事件修正延迟刷新 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.econ.Market#advance(float)}<br>
 * 注入动机：原版会在市场推进中对每个商品立刻执行 {@code reapplyEventMod()}，
 * 即便该商品在当前 tick 内无人读取。<br>
 * 注入效果：推进阶段只将商品标记为待刷新，把真正的事件修正计算延后到读取
 * {@code available} 时再执行。
 */
@Mixin(targets = GameMixinSignatures.Market.TARGET_CLASS)
public abstract class MarketMixin {
    @Redirect(
            method = GameMixinSignatures.Market.ADVANCE,
            at = @At(value = "INVOKE", target = GameMixinSignatures.CommodityOnMarket.REAPPLY_EVENT_MOD_TARGET),
            remap = false)
    private void ssoptimizer$markCommodityEventModDirty(final CommodityOnMarket commodity) {
        CommodityEventModRefreshHelper.markDirty((CommodityEventModRefreshBridge) commodity);
    }
}