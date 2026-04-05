package github.kasuminova.ssoptimizer.mixin.econ;

import com.fs.starfarer.api.combat.MutableStatWithTempMods;
import com.fs.starfarer.campaign.econ.CommodityOnMarket;
import github.kasuminova.ssoptimizer.common.campaign.econ.CommodityEventModRefreshBridge;
import github.kasuminova.ssoptimizer.common.campaign.econ.CommodityEventModRefreshHelper;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
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
    @Unique
    private boolean ssoptimizer$eventModDirty;

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
}