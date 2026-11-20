package github.kasuminova.ssoptimizer.mixin.econ;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import github.kasuminova.ssoptimizer.common.campaign.econ.MarketAdvanceThrottleBridge;
import github.kasuminova.ssoptimizer.common.campaign.econ.MarketAdvanceThrottleHelper;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = GameMixinSignatures.Economy.TARGET_CLASS)
public abstract class EconomyAdvanceThrottleMixin {
    @Redirect(
            method = GameMixinSignatures.Economy.ADVANCE,
            at = @At(value = "INVOKE", target = GameMixinSignatures.Economy.MARKET_ADVANCE_TARGET),
            remap = false)
    private void ssoptimizer$advanceMarket(final MarketAPI market, final float amount) {
        // 产业和市场条件可修改全局航线与脚本列表，必须在战役线程按原顺序推进。
        if (market instanceof MarketAdvanceThrottleBridge bridge) {
            MarketAdvanceThrottleHelper.advanceThrottled(bridge, amount);
        } else {
            market.advance(amount);
        }
    }
}
