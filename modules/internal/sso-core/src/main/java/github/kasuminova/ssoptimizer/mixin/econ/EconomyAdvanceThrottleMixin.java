package github.kasuminova.ssoptimizer.mixin.econ;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import github.kasuminova.ssoptimizer.common.campaign.econ.MarketAdvanceParallelDispatcher;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 经济体市场推进降频 + 并行调度 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.econ.Economy#advance(float)}
 * 市场循环内的 {@code market.advance(amount)} 调用点，以及方法 RETURN。<br>
 * 注入动机：{@code Market.advance} 是战役主线程的主要热点之一；原版各累加器全部线性，
 * 一次 {@code advance(n·dt)} 与 n 次 {@code advance(dt)} 在游戏日内语义等价，
 * 因此可以按帧合并转发。模组若按调用次数驱动 advance 属于风险敞口，
 * 可用 {@code -Dssoptimizer.econ.advance.interval=1} 恢复逐帧行为。<br>
 * 注入效果：每 N 次调用（默认 2）以累计 dt 转发一次真实推进，新市场首次出现立即推进；
 * 循环外的 reach 经济 stepper（{@code stepper.nextFrame}）保持原样逐帧执行，不受影响。
 * {@code -Dssoptimizer.econ.advance.parallel=true}（默认关闭）时判定通过的市场
 * 进一步分发到工作线程并行推进（玩家市场留主线程串行），RETURN 屏障保证
 * 全部市场任务完成后 {@code advance} 才返回；开关关闭时行为与纯降频逐帧等价。
 * 详见 {@link MarketAdvanceParallelDispatcher}。
 */
@Mixin(targets = GameMixinSignatures.Economy.TARGET_CLASS)
public abstract class EconomyAdvanceThrottleMixin {
    @Redirect(
            method = GameMixinSignatures.Economy.ADVANCE,
            at = @At(value = "INVOKE", target = GameMixinSignatures.Economy.MARKET_ADVANCE_TARGET),
            remap = false)
    private void ssoptimizer$dispatchMarketAdvance(final MarketAPI market, final float amount) {
        MarketAdvanceParallelDispatcher.dispatch(market, amount);
    }

    /**
     * 帧内屏障：市场循环是 {@code advance} 的最后一段，RETURN 注入等价于
     * 循环结束后立即等待全部并行市场任务完成。并行关闭时为无操作。
     */
    @Inject(method = GameMixinSignatures.Economy.ADVANCE, at = @At("RETURN"), remap = false)
    private void ssoptimizer$awaitMarketAdvance(final CallbackInfo ci) {
        MarketAdvanceParallelDispatcher.awaitAll();
    }
}
