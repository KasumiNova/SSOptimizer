package github.kasuminova.ssoptimizer.mixin.econ;

import com.fs.starfarer.campaign.econ.CommodityOnMarket;
import com.fs.starfarer.campaign.econ.Market;
import github.kasuminova.ssoptimizer.common.campaign.econ.CommodityEventModRefreshBridge;
import github.kasuminova.ssoptimizer.common.campaign.econ.CommodityEventModRefreshHelper;
import github.kasuminova.ssoptimizer.common.campaign.econ.MarketAdvanceThrottleBridge;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = GameMixinSignatures.Market.TARGET_CLASS)
public abstract class MarketMixin implements MarketAdvanceThrottleBridge {
    // 降频状态不随存档持久化，读档后按无积压处理。
    @Unique
    private transient double ssoptimizer$pendingAdvanceSeconds;
    @Unique
    private transient int    ssoptimizer$advanceCallCount;

    @Redirect(
            method = GameMixinSignatures.Market.ADVANCE,
            at = @At(value = "INVOKE", target = GameMixinSignatures.CommodityOnMarket.REAPPLY_EVENT_MOD_TARGET),
            remap = false)
    private void ssoptimizer$markCommodityEventModDirty(final CommodityOnMarket commodity) {
        // 必须在统计推进后采样，才能捕获临时修正到期与 available 的直接改写。
        CommodityEventModRefreshHelper.markDirtyIfSignatureChanged(
                (CommodityEventModRefreshBridge) commodity,
                CommodityEventModRefreshHelper.mutationGenerationOf(commodity.getAvailableStat()),
                CommodityEventModRefreshHelper.mutationGenerationOf(commodity.getTradeMod()),
                CommodityEventModRefreshHelper.mutationGenerationOf(commodity.getTradeModPlus()),
                CommodityEventModRefreshHelper.mutationGenerationOf(commodity.getTradeModMinus()));
    }

    @Override
    public double ssoptimizer$getPendingAdvanceSeconds() {
        return ssoptimizer$pendingAdvanceSeconds;
    }

    @Override
    public void ssoptimizer$setPendingAdvanceSeconds(final double seconds) {
        ssoptimizer$pendingAdvanceSeconds = seconds;
    }

    @Override
    public int ssoptimizer$getAdvanceCallCount() {
        return ssoptimizer$advanceCallCount;
    }

    @Override
    public void ssoptimizer$setAdvanceCallCount(final int count) {
        ssoptimizer$advanceCallCount = count;
    }

    @Override
    public void ssoptimizer$advanceNow(final float amount) {
        ((Market) (Object) this).advance(amount);
    }
}
