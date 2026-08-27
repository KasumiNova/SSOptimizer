package github.kasuminova.ssoptimizer.common.campaign.econ;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import github.kasuminova.ssoptimizer.common.concurrent.FrameParallelExecutor;
import github.kasuminova.ssoptimizer.common.concurrent.SharedFrameWorkers;

/**
 * 经济体市场推进并行调度器。
 * <p>
 * 织入方式：{@code EconomyAdvanceThrottleMixin} 把 {@code Economy.advance(float)}
 * 市场循环内的 {@code market.advance(amount)} 调用点重定向到 {@link #dispatch}，
 * 并在方法 RETURN 处插入 {@link #awaitAll()} 帧内屏障。
 * 循环前的 {@code updateLocationMap()} 与 reach 经济 stepper 段保持在屏障外
 * 主线程原样执行，屏障保证「stepper 全部完成 → 市场并行推进 → 全部完成后才返回」，
 * 帧内后续 intel/UI/faction 逻辑读到的市场状态与原版串行执行时同样完整。
 * <p>
 * 与降频的组合语义：降频判定（{@link MarketAdvanceThrottleHelper#decideAdvanceSeconds}）
 * 与累计 dt 状态更新始终在分发循环所在的主线程执行，判定通过的市场才把
 * 「以累计 dt 真实推进一次」作为任务投递到工作池——降频决定哪些市场本帧推进，
 * 并行决定怎么推进，dt 不丢不重。
 * <p>
 * 跨市场/全局共享写的序列化策略——玩家市场留在主线程内联推进：
 * {@code Market.advance} 路径上全部三处跨市场共享写都以 {@code market.isPlayerOwned()}
 * 为门（源码核实）：<br>
 * 1. SharedData 月报：{@code LocalResourcesSubmarketPlugin.doShortageCountering}
 * 仅 {@code market.isPlayerOwned()} 时写 {@code SharedData.getData().getCurrentReport()}；<br>
 * 2. {@code CommodityMarketData.marketShareData}：advance 路径唯一访问点是
 * {@code LocalResourcesSubmarketPlugin.shouldHaveCommodity}，而 local_resources
 * 子市场只在玩家殖民时创建（PlanetSurveyPanel），NPC 市场不持有；<br>
 * 3. 构建完成事件：{@code BaseIndustry.buildNextInQueue} 的 credits 退款 /
 * {@code CampaignUI.addMessage} / intel 全部仅 {@code market.isPlayerOwned()} 时触发。
 * 玩家市场内联在主线程按循环序推进后，三处共享写保持原版线程与原版顺序，
 * 无需延迟收集队列或 map 结构级改造；NPC 市场 advance 的全部写均为本市场私有
 * （条件 advance 空操作/自身字段、memory/people 仅自身、商品统计 per-market、
 * 子市场 RNG 实例级），可自由分发（stripeKey=null）。<br>
 * 玩家市场数量极少（玩家殖民地通常个位数），串行段开销可忽略。
 * <p>
 * 失败降级：任务异常由执行器记录并在屏障处主线程串行重跑一次（{@code Market.advance}
 * 非幂等，重跑 = 同帧推进两次，相对崩溃是可接受的单帧降级）；重跑仍失败则重抛。
 * <p>
 * 开关：{@code -Dssoptimizer.econ.advance.parallel=true} 启用（默认关闭，
 * 关闭时完全走降频内联路径，与 Wave 6 行为逐帧等价）；启用后直接使用
 * {@link SharedFrameWorkers} 共享工作池，线程数由统一属性
 * {@code ssoptimizer.workers.threads} 配置。
 * <p>
 * 已知风险敞口：模组若在自定义条件/产业/子市场插件的 advance 中写跨市场全局状态
 * （原版全部没有），并行窗口期可能竞态——本功能默认关闭，属显式 opt-in。
 */
public final class MarketAdvanceParallelDispatcher {
    /** 并行开关系统属性名。 */
    public static final String ENABLED_PROPERTY = "ssoptimizer.econ.advance.parallel";

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty(ENABLED_PROPERTY, "false"));
    private static final FrameParallelExecutor EXECUTOR = ENABLED ? SharedFrameWorkers.get() : null;

    private MarketAdvanceParallelDispatcher() {
    }

    /**
     * 市场循环织入点：降频判定后，NPC 市场投递到工作池并行推进，
     * 玩家市场内联主线程推进。栈签名与 {@code MarketAPI.advance(F)V}
     * 调用点完全一致（{@code (MarketAPI, F) -> void}）。
     */
    public static void dispatch(final MarketAPI market, final float amount) {
        dispatch(EXECUTOR, (MarketAdvanceThrottleBridge) market, market.isPlayerOwned(),
                amount, MarketAdvanceThrottleHelper.advanceInterval());
    }

    /**
     * 帧内屏障织入点：等待本帧全部并行市场推进完成；任务异常汇总后在主线程
     * 串行重跑/重抛（见 {@link FrameParallelExecutor#awaitAll()}）。
     */
    public static void awaitAll() {
        awaitAll(EXECUTOR);
    }

    /**
     * 调度核心（可测试入口）。
     * <p>
     * {@code executor == null}（并行关闭）时与
     * {@link MarketAdvanceThrottleHelper#advanceThrottled} 逐帧等价，零行为变化。
     *
     * @param executor    并行执行器；{@code null} 表示并行关闭走降频内联路径
     * @param bridge      目标市场的降频状态桥接
     * @param playerOwned 目标市场是否玩家所有（玩家市场始终主线程内联推进）
     * @param amount      本次请求推进的时长（秒）
     * @param interval    降频间隔（≥1）
     */
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
            // 玩家市场主线程内联：三处跨市场共享写保持原版线程与循环序
            bridge.ssoptimizer$advanceNow((float) dt);
            return;
        }
        final float frameDt = (float) dt;
        executor.submit(() -> bridge.ssoptimizer$advanceNow(frameDt), null);
    }

    /** 屏障核心（可测试入口）：执行器为 {@code null} 时无操作。 */
    static void awaitAll(final FrameParallelExecutor executor) {
        if (executor != null) {
            executor.awaitAll();
        }
    }

    /**
     * @return 当前生效的执行器（共享池实例）；并行关闭时为 {@code null}
     */
    static FrameParallelExecutor executor() {
        return EXECUTOR;
    }
}
