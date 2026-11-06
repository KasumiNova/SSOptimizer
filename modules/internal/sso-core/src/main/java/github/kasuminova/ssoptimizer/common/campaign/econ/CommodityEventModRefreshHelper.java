package github.kasuminova.ssoptimizer.common.campaign.econ;

import com.fs.starfarer.api.combat.MutableStatWithTempMods;
import github.kasuminova.ssoptimizer.common.combat.StatMutationBridge;

/** 将商品事件修正改为写时置脏、读取前按需刷新，并以统计修改代际识别外部变更。 */
public final class CommodityEventModRefreshHelper {
    private CommodityEventModRefreshHelper() {
    }

    public static void markDirty(final CommodityEventModRefreshBridge bridge) {
        bridge.ssoptimizer$setEventModDirty(true);
    }

    public static void ensureFreshIfDirty(final CommodityEventModRefreshBridge bridge) {
        if (!bridge.ssoptimizer$isEventModDirty()) {
            return;
        }

        bridge.ssoptimizer$reapplyEventModNow();
        bridge.ssoptimizer$setEventModDirty(false);
    }

    public static int mutationGenerationOf(final MutableStatWithTempMods stat) {
        return ((StatMutationBridge) stat).ssoptimizer$getMutationGeneration();
    }

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

    /** 签名未变化时保留已有脏标记，因为写路径可能更早完成置脏。 */
    public static void markDirtyIfSignatureChanged(
            final CommodityEventModRefreshBridge bridge,
            final int availableGen, final int tradeModGen,
            final int tradeModPlusGen, final int tradeModMinusGen) {
        if (bridge.ssoptimizer$updateTradeModSignatureAndCheckChanged(
                availableGen, tradeModGen, tradeModPlusGen, tradeModMinusGen)) {
            bridge.ssoptimizer$setEventModDirty(true);
        }
    }

    public static boolean shouldSkipReapply(final float combinedTradeQuantity, final boolean hasEventMod) {
        return combinedTradeQuantity == 0.0F && !hasEventMod;
    }
}
