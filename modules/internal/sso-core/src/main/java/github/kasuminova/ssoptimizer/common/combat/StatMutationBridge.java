package github.kasuminova.ssoptimizer.common.combat;

/**
 * {@code MutableStat} 修改代际桥接接口。
 * <p>
 * 职责：暴露由 Mixin 注入的修改代际计数器，让调用方以 O(1) 纯读取判断
 * 统计的 mod 集合/基值自上次观测以来是否发生过真实修改，避免为检测变化而
 * 调用 {@code getModifiedValue()} 触发全量重算。<br>
 * 计数语义：仅在真实改变计算结果的写路径（{@code modifyFlat} 实际写入、
 * {@code unmodify*} 实际移除、{@code setBaseValue} 基值变化等，即字节码中
 * {@code needsRecompute = true} 的赋值点）递增；同值覆写、无目标移除、
 * temp mod 的 timeRemaining 逐帧递减均不产生代际增长。
 */
public interface StatMutationBridge {
    /**
     * 查询统计的修改代际。
     *
     * @return 单调递增的修改代际计数；两次读取值不同则期间发生过真实修改
     */
    int ssoptimizer$getMutationGeneration();
}
