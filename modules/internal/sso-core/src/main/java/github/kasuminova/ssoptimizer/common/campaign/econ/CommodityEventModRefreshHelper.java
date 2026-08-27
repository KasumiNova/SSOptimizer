package github.kasuminova.ssoptimizer.common.campaign.econ;

import com.fs.starfarer.api.combat.MutableStatWithTempMods;
import github.kasuminova.ssoptimizer.common.combat.StatMutationBridge;

/**
 * {@code CommodityOnMarket} 事件修正的延迟刷新 helper。
 * <p>
 * 职责：把原版“每次变更立刻重算 eMod”的模型改为“写时置脏、读时按需刷新”，
 * 以减少战役经济推进中对大批量商品执行的重复计算。<br>
 * 设计动机：热点报告显示 {@code CommodityOnMarket.reapplyEventMod()} 在
 * {@code Market.advance()} 的全量循环中占据显著 CPU 时间，而多数商品在单个 tick
 * 内并不会立即被读取。<br>
 * 效果：保持单线程一致性语义不变，但把事件修正计算延后到真正读取
 * {@code available} 数据时再执行。
 */
public final class CommodityEventModRefreshHelper {
    private CommodityEventModRefreshHelper() {
    }

    /**
     * 将商品事件修正标记为待刷新。
     *
     * @param bridge 目标商品桥接对象
     */
    public static void markDirty(final CommodityEventModRefreshBridge bridge) {
        bridge.ssoptimizer$setEventModDirty(true);
    }

    /**
     * 若商品事件修正仍处于脏状态，则在读取前同步刷新一次。
     *
     * @param bridge 目标商品桥接对象
     */
    public static void ensureFreshIfDirty(final CommodityEventModRefreshBridge bridge) {
        if (!bridge.ssoptimizer$isEventModDirty()) {
            return;
        }

        bridge.ssoptimizer$reapplyEventModNow();
        bridge.ssoptimizer$setEventModDirty(false);
    }

    /**
     * 读取统计的修改代际（O(1) 纯读取，不触发重算）。
     *
     * @param stat 目标统计（运行期经 Mixin 实现 {@link StatMutationBridge}）
     * @return 当前修改代际
     */
    public static int mutationGenerationOf(final MutableStatWithTempMods stat) {
        return ((StatMutationBridge) stat).ssoptimizer$getMutationGeneration();
    }

    /**
     * 判断修改代际签名是否发生变化。
     *
     * @param initialized        是否已有上次记录（读档/新造商品的首帧为 {@code false}）
     * @param prevAvailableGen   上次记录的 {@code available} 代际
     * @param prevTradeModGen    上次记录的 {@code tradeMod} 代际
     * @param prevTradePlusGen   上次记录的 {@code tradeModPlus} 代际
     * @param prevTradeMinusGen  上次记录的 {@code tradeModMinus} 代际
     * @param availableGen       本次采样的 {@code available} 代际
     * @param tradeModGen        本次采样的 {@code tradeMod} 代际
     * @param tradeModPlusGen    本次采样的 {@code tradeModPlus} 代际
     * @param tradeModMinusGen   本次采样的 {@code tradeModMinus} 代际
     * @return 首次记录或任一分量变化时返回 {@code true}
     */
    public static boolean isTradeModSignatureChanged(
            final boolean initialized,
            final int prevAvailableGen, final int prevTradeModGen,
            final int prevTradePlusGen, final int prevTradeMinusGen,
            final int availableGen, final int tradeModGen,
            final int tradeModPlusGen, final int tradeModMinusGen) {
        return !initialized
                || prevAvailableGen != availableGen
                || prevTradeModGen != tradeModGen
                || prevTradePlusGen != tradeModPlusGen
                || prevTradeMinusGen != tradeModMinusGen;
    }

    /**
     * 市场推进阶段的签名化置脏：仅当修改代际签名与上次记录不同才置脏。
     * <p>
     * 检测点位于四个统计的 {@code advance()} 之后，temp mod 到期与
     * {@code available} 的直接改写都能在此被捕捉；签名未变化时不清除既有脏标记
     * （写路径置脏可能先于本次检测发生）。
     *
     * @param bridge           目标商品桥接对象
     * @param availableGen     {@code available} 的修改代际
     * @param tradeModGen      {@code tradeMod} 的修改代际
     * @param tradeModPlusGen  {@code tradeModPlus} 的修改代际
     * @param tradeModMinusGen {@code tradeModMinus} 的修改代际
     */
    public static void markDirtyIfSignatureChanged(
            final CommodityEventModRefreshBridge bridge,
            final int availableGen, final int tradeModGen,
            final int tradeModPlusGen, final int tradeModMinusGen) {
        if (bridge.ssoptimizer$updateTradeModSignatureAndCheckChanged(
                availableGen, tradeModGen, tradeModPlusGen, tradeModMinusGen)) {
            bridge.ssoptimizer$setEventModDirty(true);
        }
    }

    /**
     * 判断本次 {@code reapplyEventMod()} 是否必然是无副作用的空操作。
     * <p>
     * 语义依据：原版在 combinedTradeQuantity 为 0 时仅执行 {@code unmodifyFlat("eMod")}，
     * 若 {@code available} 上本就不存在 {@code eMod} 修正，则该调用不触碰任何状态。
     *
     * @param combinedTradeQuantity 当前 combinedTradeQuantity
     * @param hasEventMod           {@code available} 上是否存在 {@code eMod} 修正
     * @return 若本次刷新必然无副作用则返回 {@code true}
     */
    public static boolean shouldSkipReapply(final float combinedTradeQuantity, final boolean hasEventMod) {
        return combinedTradeQuantity == 0.0F && !hasEventMod;
    }
}
