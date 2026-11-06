package github.kasuminova.ssoptimizer.common.campaign.econ;

/** Mixin 注入的商品脏标记、修改代际签名与事件修正刷新动作。 */
public interface CommodityEventModRefreshBridge {
    boolean ssoptimizer$isEventModDirty();

    void ssoptimizer$setEventModDirty(boolean dirty);

    void ssoptimizer$reapplyEventModNow();

    /** 每次调用都必须更新签名记录；首次记录或任一代际变化时返回 true。 */
    boolean ssoptimizer$updateTradeModSignatureAndCheckChanged(
            int availableGen, int tradeModGen, int tradeModPlusGen, int tradeModMinusGen);
}
