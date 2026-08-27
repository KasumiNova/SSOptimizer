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

/**
 * 市场推进阶段的商品事件修正延迟刷新 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.econ.Market#advance(float)}<br>
 * 注入动机：原版会在市场推进中对每个商品立刻执行 {@code reapplyEventMod()}，
 * 即便该商品的事件修正输入在当前 tick 内毫无变化。<br>
 * 注入效果：推进阶段（四个统计 {@code advance()} 之后、temp mod 到期已发生）
 * 采样四个统计的修改代际签名，仅当签名与上次记录不同才将商品标记为待刷新，
 * 把真正的事件修正计算延后到读取 {@code available} 时再执行。
 * 代际读取为 O(1) 纯字段访问，不触发任何统计重算，签名比较无堆分配。
 * <p>
 * 同时承载 {@link MarketAdvanceThrottleBridge} 的降频状态（累计待推进时长与调用计数），
 * 供 {@code EconomyAdvanceThrottleMixin} 合并市场推进使用。
 */
@Mixin(targets = GameMixinSignatures.Market.TARGET_CLASS)
public abstract class MarketMixin implements MarketAdvanceThrottleBridge {
    @Unique
    private double ssoptimizer$pendingAdvanceSeconds;
    @Unique
    private int    ssoptimizer$advanceCallCount;

    @Redirect(
            method = GameMixinSignatures.Market.ADVANCE,
            at = @At(value = "INVOKE", target = GameMixinSignatures.CommodityOnMarket.REAPPLY_EVENT_MOD_TARGET),
            remap = false)
    private void ssoptimizer$markCommodityEventModDirty(final CommodityOnMarket commodity) {
        // 采样点位于 4×stat.advance() 之后：temp mod 到期与 available 的直接改写
        // 都能被代际签名捕捉，eMod 相对到期不再滞后一帧；
        // getAvailableStat() 的 HEAD 注入会让待刷新商品先在此处（advance 之后）完成重算
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
