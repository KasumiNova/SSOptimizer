package github.kasuminova.ssoptimizer.common.campaign.econ;

/**
 * {@code CommodityOnMarket} 事件修正延迟刷新的桥接接口。
 * <p>
 * 职责：为 Mixin 注入的脏标记状态与实际刷新动作提供统一抽象，
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
}