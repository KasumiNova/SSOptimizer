package github.kasuminova.ssoptimizer.common.campaign.econ;

/** Mixin 注入的市场降频状态与真实推进动作，全部方法仅限战役主线程调用。 */
public interface MarketAdvanceThrottleBridge {
    double ssoptimizer$getPendingAdvanceSeconds();

    void ssoptimizer$setPendingAdvanceSeconds(double seconds);

    int ssoptimizer$getAdvanceCallCount();

    void ssoptimizer$setAdvanceCallCount(int count);

    void ssoptimizer$advanceNow(float amount);
}
