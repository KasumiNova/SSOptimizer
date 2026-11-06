package github.kasuminova.ssoptimizer.mixin.econ;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import github.kasuminova.ssoptimizer.common.campaign.econ.MarketAdvanceParallelDispatcher;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = GameMixinSignatures.Economy.TARGET_CLASS)
public abstract class EconomyAdvanceThrottleMixin {
    @Redirect(
            method = GameMixinSignatures.Economy.ADVANCE,
            at = @At(value = "INVOKE", target = GameMixinSignatures.Economy.MARKET_ADVANCE_TARGET),
            remap = false)
    private void ssoptimizer$dispatchMarketAdvance(final MarketAPI market, final float amount) {
        MarketAdvanceParallelDispatcher.dispatch(market, amount);
    }

    /** RETURN 屏障保证并行市场任务在 economy advance 返回前完成。 */
    @Inject(method = GameMixinSignatures.Economy.ADVANCE, at = @At("RETURN"), remap = false)
    private void ssoptimizer$awaitMarketAdvance(final CallbackInfo ci) {
        MarketAdvanceParallelDispatcher.awaitAll();
    }
}
