package github.kasuminova.ssoptimizer.common.combat;

/**
 * 暴露 Mixin 注入的 {@code MutableStat} 修改代际，供调用方低成本检测计算结果是否变化。
 * 仅在真实修改计算结果时递增，同值覆写、无效移除和临时修正倒计时不会递增。
 */
public interface StatMutationBridge {
    /**
     * 查询统计的修改代际。
     *
     * @return 单调递增的修改代际计数；两次读取值不同则期间发生过真实修改
     */
    int ssoptimizer$getMutationGeneration();
}
