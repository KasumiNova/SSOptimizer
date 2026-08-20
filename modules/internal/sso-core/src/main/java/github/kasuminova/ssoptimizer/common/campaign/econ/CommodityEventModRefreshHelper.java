package github.kasuminova.ssoptimizer.common.campaign.econ;

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
}