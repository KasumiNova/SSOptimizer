package github.kasuminova.ssoptimizer.common.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;

/**
 * 加载期噪音日志的 log4j2 层聚合过滤器（生产链路真实生效的实现）。
 *
 * <p>为什么必须在 log4j2 层：游戏代码经 {@code org.apache.log4j.Logger} 打日志，运行时由
 * SSOptimizer shade 的 log4j-1.2-api 桥接转发到 NanoForge 提供的 log4j2。桥接的
 * {@code Category.callAppenders(LoggingEvent)} 在检测到 log4j2 core 存在时直接调用
 * {@code CategoryUtil.log(logger, new LogEventWrapper(event))} 映射到 log4j2 Logger，
 * <b>完全绕过</b> log4j 1.x 的 appender 链与 {@code org.apache.log4j.spi.Filter.decide}。
 * 因此 1.x Filter 在游戏运行时无效，消息级过滤/聚合必须挂在 log4j2 的 root
 * LoggerConfig Filter 链上（本类），由 {@code VanillaLogNoiseConfigurator} 在 coremod
 * onLoad 阶段安装。1.x 侧的等语义实现 {@link LoadingNoiseLog4j1Filter} 仅用于真实
 * log4j-1.2.17 独立 JVM 的端到端验证（工具脚本），不进生产链路。</p>
 *
 * <p>行为：WARN/ERROR 不聚合不压制（错误日志必须保留）；INFO 消息命中聚合模式
 * （{@link LoadingNoiseAggregator#NOISE_PATTERNS}）返回 DENY 并计数；非聚合消息或
 * WARN/ERROR 到达时先 flush 累计计数再放行，加载期结束后的第一批日志即触发汇总输出。</p>
 */
public final class LoadingNoiseLog4j2Filter extends AbstractFilter {
    /** 汇总行输出 logger（SSOptimizer 自身命名空间，不受任何噪音压制影响）。 */
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger(LoadingNoiseLog4j2Filter.class);

    /** 汇总行输出通道（包级可注入，单测不依赖真实 appender）。 */
    private final LoadingNoiseAggregator aggregator;

    /** 生产构造：汇总行经 SSOptimizer 自身 logger 输出。 */
    public LoadingNoiseLog4j2Filter() {
        this(new LoadingNoiseAggregator(LOGGER::info));
    }

    /** 包级可见：注入 reporter 供单测捕获汇总行。 */
    LoadingNoiseLog4j2Filter(LoadingNoiseAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Override
    public Result filter(LogEvent event) {
        if (event == null) {
            return Result.NEUTRAL;
        }
        if (!event.getLevel().isLessSpecificThan(Level.WARN)) {
            // WARN/ERROR：先 flush 累计统计（加载期结束信号），自身不压制
            aggregator.flush();
            return Result.NEUTRAL;
        }
        final org.apache.logging.log4j.message.Message message = event.getMessage();
        final String rendered = message == null ? null : message.getFormattedMessage();
        if (aggregator.decideSuppress(rendered)) {
            return Result.DENY;
        }
        // 非聚合 INFO：flush 前一组计数后放行
        aggregator.flush();
        return Result.NEUTRAL;
    }
}
