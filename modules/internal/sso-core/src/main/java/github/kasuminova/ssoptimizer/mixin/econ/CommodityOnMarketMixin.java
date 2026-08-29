package github.kasuminova.ssoptimizer.mixin.econ;

import com.fs.starfarer.api.combat.MutableStatWithTempMods;
import com.fs.starfarer.campaign.econ.CommodityOnMarket;
import github.kasuminova.ssoptimizer.common.campaign.econ.CommodityEventModRefreshBridge;
import github.kasuminova.ssoptimizer.common.campaign.econ.CommodityEventModRefreshHelper;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 市场商品事件修正延迟刷新 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.econ.CommodityOnMarket}<br>
 * 注入动机：原版在添加贸易事件修正时会立即重算 {@code available} 上的 {@code eMod}，
 * 与市场推进中的全量重算叠加后形成显著热点。<br>
 * 注入效果：改为在写路径上只置脏，在读取 {@code available} 相关结果时再按需刷新，
 * 以减少无消费 tick 上的重复计算。
 */
@Mixin(targets = GameMixinSignatures.CommodityOnMarket.TARGET_CLASS)
public abstract class CommodityOnMarketMixin implements CommodityEventModRefreshBridge {
    @Shadow
    private MutableStatWithTempMods available;

    @Unique
    private transient boolean ssoptimizer$eventModDirty;

    /**
     * 上次市场推进记录的修改代际签名（四个统计各一）。
     * <p>
     * 全部 transient：{@code CommodityOnMarket} 随市场进战役存档，注入字段为
     * 运行期派生状态，不随存档持久化；读档后
     * {@code ssoptimizer$tradeModSignatureInitialized} 为 JVM 默认值
     * {@code false}，首次推进视为签名变化，置脏一次并重建记录（安全方向：
     * 多读一次刷新，不会漏刷新）。
     */
    @Unique
    private transient boolean ssoptimizer$tradeModSignatureInitialized;
    @Unique
    private transient int     ssoptimizer$sigAvailableGen;
    @Unique
    private transient int     ssoptimizer$sigTradeModGen;
    @Unique
    private transient int     ssoptimizer$sigTradeModPlusGen;
    @Unique
    private transient int     ssoptimizer$sigTradeModMinusGen;

    /**
     * {@code reapplyEventMod()} 空操作快速路径。
     * <p>
     * 动机：combinedTradeQuantity 为 0 且 {@code available} 上无 {@code eMod} 时，
     * 原版方法体只做一次无副作用的 {@code unmodifyFlat("eMod")}，直接跳过。<br>
     * 注意：此处必须走 {@link #available} 字段与 {@code getCombinedTradeModQuantity()}，
     * 不得调用 {@code getAvailableStat()}——其 HEAD 注入的 ensureFresh 会在脏状态下
     * 回调 {@code reapplyEventMod()} 造成无限递归。
     */
    @Inject(method = GameMixinSignatures.CommodityOnMarket.REAPPLY_EVENT_MOD,
            at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$skipNoopReapply(final CallbackInfo callbackInfo) {
        final boolean hasEventMod = available.getFlatMods().get("eMod") != null;
        if (!CommodityEventModRefreshHelper.shouldSkipReapply(
                ((CommodityOnMarket) (Object) this).getCombinedTradeModQuantity(), hasEventMod)) {
            return;
        }

        ssoptimizer$eventModDirty = false;
        callbackInfo.cancel();
    }

    @Redirect(
            method = GameMixinSignatures.CommodityOnMarket.ADD_TRADE_MOD,
            at = @At(value = "INVOKE", target = GameMixinSignatures.CommodityOnMarket.REAPPLY_EVENT_MOD_TARGET),
            remap = false)
    private void ssoptimizer$markDirtyAfterTradeMod(final CommodityOnMarket commodity) {
        CommodityEventModRefreshHelper.markDirty((CommodityEventModRefreshBridge) commodity);
    }

    @Redirect(
            method = GameMixinSignatures.CommodityOnMarket.ADD_TRADE_MOD_PLUS,
            at = @At(value = "INVOKE", target = GameMixinSignatures.CommodityOnMarket.REAPPLY_EVENT_MOD_TARGET),
            remap = false)
    private void ssoptimizer$markDirtyAfterTradeModPlus(final CommodityOnMarket commodity) {
        CommodityEventModRefreshHelper.markDirty((CommodityEventModRefreshBridge) commodity);
    }

    @Redirect(
            method = GameMixinSignatures.CommodityOnMarket.ADD_TRADE_MOD_MINUS,
            at = @At(value = "INVOKE", target = GameMixinSignatures.CommodityOnMarket.REAPPLY_EVENT_MOD_TARGET),
            remap = false)
    private void ssoptimizer$markDirtyAfterTradeModMinus(final CommodityOnMarket commodity) {
        CommodityEventModRefreshHelper.markDirty((CommodityEventModRefreshBridge) commodity);
    }

    @Inject(method = GameMixinSignatures.CommodityOnMarket.GET_AVAILABLE,
            at = @At("HEAD"), remap = false)
    private void ssoptimizer$ensureFreshAvailable(final CallbackInfoReturnable<Integer> callbackInfo) {
        CommodityEventModRefreshHelper.ensureFreshIfDirty(this);
    }

    @Inject(method = GameMixinSignatures.CommodityOnMarket.GET_AVAILABLE_STAT,
            at = @At("HEAD"), remap = false)
    private void ssoptimizer$ensureFreshAvailableStat(final CallbackInfoReturnable<MutableStatWithTempMods> callbackInfo) {
        CommodityEventModRefreshHelper.ensureFreshIfDirty(this);
    }

    @Inject(method = GameMixinSignatures.CommodityOnMarket.REAPPLY_EVENT_MOD,
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$clearDirtyFlag(final CallbackInfo callbackInfo) {
        ssoptimizer$eventModDirty = false;
    }

    @Override
    public boolean ssoptimizer$isEventModDirty() {
        return ssoptimizer$eventModDirty;
    }

    @Override
    public void ssoptimizer$setEventModDirty(final boolean dirty) {
        ssoptimizer$eventModDirty = dirty;
    }

    @Override
    public void ssoptimizer$reapplyEventModNow() {
        ((CommodityOnMarket) (Object) this).reapplyEventMod();
    }

    @Override
    public boolean ssoptimizer$updateTradeModSignatureAndCheckChanged(
            final int availableGen, final int tradeModGen,
            final int tradeModPlusGen, final int tradeModMinusGen) {
        final boolean changed = CommodityEventModRefreshHelper.isTradeModSignatureChanged(
                ssoptimizer$tradeModSignatureInitialized,
                ssoptimizer$sigAvailableGen, ssoptimizer$sigTradeModGen,
                ssoptimizer$sigTradeModPlusGen, ssoptimizer$sigTradeModMinusGen,
                availableGen, tradeModGen, tradeModPlusGen, tradeModMinusGen);

        ssoptimizer$tradeModSignatureInitialized = true;
        ssoptimizer$sigAvailableGen = availableGen;
        ssoptimizer$sigTradeModGen = tradeModGen;
        ssoptimizer$sigTradeModPlusGen = tradeModPlusGen;
        ssoptimizer$sigTradeModMinusGen = tradeModMinusGen;
        return changed;
    }
}