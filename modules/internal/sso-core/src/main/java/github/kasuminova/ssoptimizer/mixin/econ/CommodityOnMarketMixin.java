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

@Mixin(targets = GameMixinSignatures.CommodityOnMarket.TARGET_CLASS)
public abstract class CommodityOnMarketMixin implements CommodityEventModRefreshBridge {
    @Shadow
    private MutableStatWithTempMods available;

    @Unique
    private transient boolean ssoptimizer$eventModDirty;

    /** 签名不随存档持久化，读档后的首次推进会强制置脏并重建记录。 */
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

    /** 快速路径必须绕过 getAvailableStat()，避免脏状态刷新递归调用本方法。 */
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