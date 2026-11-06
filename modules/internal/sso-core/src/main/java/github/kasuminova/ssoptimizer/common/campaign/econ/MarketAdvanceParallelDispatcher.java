package github.kasuminova.ssoptimizer.common.campaign.econ;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import github.kasuminova.ssoptimizer.common.concurrent.FrameParallelExecutor;
import github.kasuminova.ssoptimizer.common.concurrent.SharedFrameWorkers;

/**
 * 并行推进 NPC 市场，玩家市场仍在战役主线程按原顺序内联推进，避免跨市场共享写竞态。
 * 降频判定与累计时长更新只在主线程执行；{@link #awaitAll()} 必须在同帧返回前调用。
 * 模组自定义市场逻辑若写跨市场全局状态，开启并行后可能产生竞态。
 */
public final class MarketAdvanceParallelDispatcher {
    public static final String ENABLED_PROPERTY = "ssoptimizer.econ.advance.parallel";

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty(ENABLED_PROPERTY, "false"));
    private static final FrameParallelExecutor EXECUTOR = ENABLED ? SharedFrameWorkers.get() : null;

    private MarketAdvanceParallelDispatcher() {
    }

    public static void dispatch(final MarketAPI market, final float amount) {
        dispatch(EXECUTOR, (MarketAdvanceThrottleBridge) market, market.isPlayerOwned(),
                amount, MarketAdvanceThrottleHelper.advanceInterval());
    }

    public static void awaitAll() {
        awaitAll(EXECUTOR);
    }

    static void dispatch(final FrameParallelExecutor executor, final MarketAdvanceThrottleBridge bridge,
                         final boolean playerOwned, final float amount, final int interval) {
        if (executor == null) {
            MarketAdvanceThrottleHelper.advanceThrottled(bridge, amount, interval);
            return;
        }
        final double dt = MarketAdvanceThrottleHelper.decideAdvanceSeconds(bridge, amount, interval);
        if (Double.isNaN(dt)) {
            return;
        }
        if (playerOwned) {
            // 玩家市场存在跨市场共享写，必须保持主线程内联与循环顺序。
            bridge.ssoptimizer$advanceNow((float) dt);
            return;
        }
        final float frameDt = (float) dt;
        executor.submit(() -> bridge.ssoptimizer$advanceNow(frameDt), null);
    }

    static void awaitAll(final FrameParallelExecutor executor) {
        if (executor != null) {
            executor.awaitAll();
        }
    }

    static FrameParallelExecutor executor() {
        return EXECUTOR;
    }
}
