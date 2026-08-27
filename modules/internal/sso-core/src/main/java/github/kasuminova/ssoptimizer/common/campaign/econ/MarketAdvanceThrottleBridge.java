package github.kasuminova.ssoptimizer.common.campaign.econ;

/**
 * {@code Market} 推进降频状态的桥接接口。
 * <p>
 * 职责：为 Mixin 注入的降频状态（累计待推进时长、推进调用计数）与真实推进动作
 * 提供统一抽象，让 helper 不必直接依赖注入类实现细节。<br>
 * 线程模型：{@code Economy.advance} 仅在战役主线程执行，全部状态读写单线程确定，
 * 无需任何同步原语。
 */
public interface MarketAdvanceThrottleBridge {
    /**
     * 查询累计待推进时长。
     * <p>
     * 以 double 累计被降频合并的 {@code amount}（秒），消除逐帧 float 累加漂移。
     *
     * @return 自上次真实推进以来累计的待推进秒数
     */
    double ssoptimizer$getPendingAdvanceSeconds();

    /**
     * 更新累计待推进时长。
     *
     * @param seconds 新的待推进秒数（真实推进转发后归零）
     */
    void ssoptimizer$setPendingAdvanceSeconds(double seconds);

    /**
     * 查询该市场被请求推进的累计次数（含被降频合并、未真实转发的次数）。
     *
     * @return 推进调用计数；{@code 0} 表示该市场首次出现
     */
    int ssoptimizer$getAdvanceCallCount();

    /**
     * 更新推进调用计数。
     *
     * @param count 新的调用计数
     */
    void ssoptimizer$setAdvanceCallCount(int count);

    /**
     * 立刻执行一次原版 {@code advance(amount)}。
     *
     * @param amount 推进时长（秒），为累计待推进时长的 float 化结果
     */
    void ssoptimizer$advanceNow(float amount);
}
