package github.kasuminova.ssoptimizer.api.save;

/**
 * 向自动化模块提供 {@code campaign.xml} 反序列化阶段的最近完成耗时，避免跨模块读取计时器实现。
 * <p>
 * 查询无副作用，可由自动化线程与存档线程并发访问，实现须保证结果的跨线程可见性。
 * 未注册 = 遥测不可用，依赖该指标的调用方应报告服务缺失，不得按零耗时处理。
 */
public interface SavePhaseTelemetry {

    /**
     * 读取最近一次已完成的反序列化阶段耗时，不触发读档或重置计时。
     *
     * @return 最近一次反序列化阶段的耗时，单位为毫秒；尚未完成任何读档反序列化时返回 {@code -1}
     */
    long lastUnmarshalMs();
}
