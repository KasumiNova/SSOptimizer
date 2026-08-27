package github.kasuminova.ssoptimizer.common.campaign.econ;

/**
 * {@code CommodityOnMarket} 事件修正延迟刷新的桥接接口。
 * <p>
 * 职责：为 Mixin 注入的脏标记状态、修改代际签名与实际刷新动作提供统一抽象，
 * 让业务 helper 不必直接依赖注入类实现细节。
 */
public interface CommodityEventModRefreshBridge {
    /**
     * 查询事件修正是否处于脏状态。
     *
     * @return 若仍需刷新 {@code available} 上的事件修正则返回 {@code true}
     */
    boolean ssoptimizer$isEventModDirty();

    /**
     * 更新事件修正脏状态。
     *
     * @param dirty 新的脏状态
     */
    void ssoptimizer$setEventModDirty(boolean dirty);

    /**
     * 立刻执行一次原版事件修正刷新。
     */
    void ssoptimizer$reapplyEventModNow();

    /**
     * 用本次市场推进采样的修改代际更新签名记录，并报告签名是否变化。
     * <p>
     * 签名由四个统计（{@code available/tradeMod/tradeModPlus/tradeModMinus}）的
     * 修改代际组成：代际仅在真实修改时递增（见
     * {@link github.kasuminova.ssoptimizer.common.combat.StatMutationBridge}），
     * temp mod 的 timeRemaining 逐帧递减不影响签名。<br>
     * 实现方无论签名是否变化都必须更新内部记录（首次调用视为变化）。
     *
     * @param availableGen     {@code available} 的修改代际
     * @param tradeModGen      {@code tradeMod} 的修改代际
     * @param tradeModPlusGen  {@code tradeModPlus} 的修改代际
     * @param tradeModMinusGen {@code tradeModMinus} 的修改代际
     * @return 签名与上次记录不同（含首次记录）则返回 {@code true}
     */
    boolean ssoptimizer$updateTradeModSignatureAndCheckChanged(
            int availableGen, int tradeModGen, int tradeModPlusGen, int tradeModMinusGen);
}
