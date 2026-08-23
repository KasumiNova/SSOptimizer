package github.kasuminova.ssoptimizer.common.logging;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.util.Locale;

/**
 * 压制原版游戏启动/加载阶段的高频低价值 INFO 日志（资源/规则/脚本/纹理/声音加载等），
 * 保留 WARN/ERROR、SSOptimizer 自身日志与加载失败诊断（not found 等），避免污染用户检索
 * 关键输出。
 *
 * <p>名单来自 starsector.log 实证统计 + named jar 类路径核对：加载期（SpecLoad 阶段）由
 * {@code com.fs.starfarer.loading.*}/{@code scripts.ScriptStore}/{@code rules.Rules}/
 * {@code com.fs.graphics.TextureLoader}/{@code sound.*}/{@code launcher.ModManager}/
 * {@code codex.CodexTextEntryLoader} 等 logger 打出海量「Loading ...」「Applying data ...」
 * 「Class ... already loaded ... skipping compilation」等单条无信息量日志（单次启动可达
 * 14.8 万行以上，占总日志约六成）。注意 {@code util.TextureData} 等仅输出 ERROR 的 logger
 * 不在名单内，缺失资源的报错必须保留；第三方 mod 的 logger（如 {@code util.ShipColors}）
 * 也不在名单内。</p>
 *
 * <p>名单使用运行时 logger 全名（FQCN）：游戏代码经 {@code Logger.getLogger(Class)} 创建
 * logger，log4j-1.2-api 桥接取 {@code Class.getName()}，故 {@code loading.*} 的完整名是
 * {@code com.fs.starfarer.loading.*}（日志中 {@code %c{2}} 截断显示）；仅 {@code sound.*}
 * 因位于 fs.sound_obf 顶层 {@code sound} 包而使用短名。若名单误用截断短名，setLevel 设置的
 * 是另一个无关 logger，过滤在生产运行时不生效（Gradle 单测环境无法暴露——见测试类注释）。</p>
 *
 * <p>机制（消息级聚合压制）：名单 logger 统一保持 INFO 可见，逐条压制交给消息级聚合过滤器
 * {@link LoadingNoiseAggregator}（数据驱动模式清单）——匹配的刷屏行被 DENY 并计数，加载期
 * 结束（首条非聚合日志/WARN 到达）时 flush 成「Loaded N &lt;分类&gt;」汇总 INFO。相比旧版
 * 整 logger 阈值压制（{@code setLevel(WARN)}），本机制保留 {@code not found}、
 * {@code Getting ready to load jar file} 等单条诊断 INFO。</p>
 *
 * <p>为什么挂 log4j2 层：游戏运行时由 SSOptimizer shade 的 log4j-1.2-api 桥接转发到
 * NanoForge 的 log4j2，桥接的 {@code Category.callAppenders} 在 log4j2 core 存在时直接
 * 映射到 log4j2 Logger、绕过 log4j 1.x 的 Filter/appender 链；故聚合过滤器挂到 log4j2
 * root LoggerConfig（{@link LoadingNoiseLog4j2Filter}）。此处 {@code setLevel(INFO)} 经
 * log4j-1.2-api 的 {@code CategoryUtil.setLevel} 映射到 log4j2 LoggerConfig 即时生效，
 * 与 Filter 挂载配合完成整条压制链路。</p>
 *
 * <p>默认开启。通过 {@code -Dssoptimizer.logging.vanilla.level=INFO}（或 {@code DEBUG}）
 * 恢复原版完整加载日志（不装聚合过滤器）。调用点必须早于原版加载期日志产生——由 coremod
 * {@code SSOptimizerCorePlugin.onLoad}（早于任何游戏类加载）触发，而非
 * {@code SSOptimizerModPlugin.onApplicationLoad}（晚于加载期）。</p>
 */
public final class VanillaLogNoiseConfigurator {
    /** 原版加载噪音日志阈值属性；设 {@code INFO}/{@code DEBUG} 恢复完整日志。 */
    public static final String VANILLA_LEVEL_PROPERTY = "ssoptimizer.logging.vanilla.level";

    private static final Logger LOGGER = Logger.getLogger(VanillaLogNoiseConfigurator.class);

    /** 原版加载期噪音 logger 全名清单（来自 starsector.log 实证统计 + named jar 类路径核对；
     *  必须使用运行时 logger 全名（FQCN，sound.* 例外见类注释），不得使用日志显示截断名；
     *  包级可见供单测守护名单契约——不得混入仅输出 ERROR 的 logger、SSOptimizer 自身 logger
     *  或第三方 mod logger）。 */
    static final String[] NOISE_LOGGER_NAMES = {
            "com.fs.starfarer.loading.LoadingUtils",
            "com.fs.starfarer.loading.SpecStore",
            "com.fs.starfarer.loading.WeaponSpreadsheetLoader",
            "com.fs.starfarer.loading.WeaponSpecLoader",
            "com.fs.starfarer.loading.ShipHullSpreadsheetLoader",
            "com.fs.starfarer.loading.ShipHullSpecLoader",
            "com.fs.starfarer.loading.FighterWingSpreadsheetLoader",
            "com.fs.starfarer.loading.HullVariantSpecStore",
            "com.fs.starfarer.loading.ShipNameStore",
            "com.fs.starfarer.loading.scripts.ScriptStore",
            "com.fs.starfarer.loading.scripts.ScriptClassLoader",
            "com.fs.starfarer.campaign.rules.Rules",
            "com.fs.graphics.TextureLoader",
            "sound.Sound",
            "sound.Music",
            "com.fs.starfarer.launcher.ModManager",
            "com.fs.starfarer.api.impl.codex.CodexTextEntryLoader",
    };

    private VanillaLogNoiseConfigurator() {
    }

    /**
     * 对全部原版加载噪音 logger 应用日志阈值并装配消息级聚合过滤器。
     * <p>
     * 阈值为 DEBUG 或更低时视为用户显式要求保留完整原版加载日志，不做任何修改
     * （与 LunaLib 阈值语义一致）。{@code WARN}（默认）或 {@code INFO} 均将名单 logger
     * 保持为 INFO 可见：{@code WARN} 额外安装聚合过滤器（逐条压制刷屏行 + 汇总替代），
     * {@code INFO} 不安装（效果等同恢复完整加载日志，与旧语义一致）。
     */
    public static void configure() {
        final Level threshold = vanillaThreshold();
        if (threshold.toInt() <= Level.DEBUG_INT) {
            LOGGER.info("[SSOptimizer] Vanilla loading log passthrough enabled at " + threshold);
            return;
        }

        for (String loggerName : NOISE_LOGGER_NAMES) {
            Logger.getLogger(loggerName).setLevel(Level.INFO);
        }

        if (threshold.toInt() >= Level.WARN_INT) {
            installAggregateFilter();
            LOGGER.info("[SSOptimizer] Vanilla loading noise aggregation enabled across "
                    + LoadingNoiseAggregator.NOISE_PATTERNS.size() + " message pattern(s) over "
                    + NOISE_LOGGER_NAMES.length + " logger(s)");
        } else {
            LOGGER.info("[SSOptimizer] Vanilla loading log passthrough enabled at INFO (aggregation off)");
        }
    }

    /**
     * 把聚合过滤器挂到 log4j2 root LoggerConfig 的 Filter 链头（幂等：已挂载则跳过）。
     * <p>
     * log4j2 中所有日志事件最终由 root LoggerConfig 处理（NanoForge log4j2.xml 仅配置
     * root），挂载其 Filter 链可对全部事件生效；{@code updateLoggers} 让新配置即时生效。
     * 无 log4j2 core 的环境（如 Gradle 单测 worker）下 {@code getContext} 返回的 context
     * 仍可正常取 root config，挂载无副作用。
     */
    private static void installAggregateFilter() {
        final LoggerContext context = (LoggerContext) LogManager.getContext(false);
        final LoggerConfig rootConfig = context.getConfiguration().getRootLogger();
        final Filter existing = rootConfig.getFilter();
        if (existing instanceof LoadingNoiseLog4j2Filter) {
            return;
        }
        rootConfig.addFilter(new LoadingNoiseLog4j2Filter());
        context.updateLoggers();
    }

    /**
     * 解析原版加载噪音日志阈值。
     *
     * @return 属性未设置/空白/非法时回退 {@link Level#WARN}；{@code TRACE} 视为 {@link Level#DEBUG}
     *         （log4j 1.x 无 TRACE 级别，与 LunaLib 阈值解析行为一致）
     */
    static Level vanillaThreshold() {
        final String configured = System.getProperty(VANILLA_LEVEL_PROPERTY, "WARN");
        if (configured == null || configured.isBlank()) {
            return Level.WARN;
        }

        final String normalized = configured.trim().toUpperCase(Locale.ROOT);
        if ("TRACE".equals(normalized)) {
            return Level.DEBUG;
        }
        return Level.toLevel(normalized, Level.WARN);
    }
}
