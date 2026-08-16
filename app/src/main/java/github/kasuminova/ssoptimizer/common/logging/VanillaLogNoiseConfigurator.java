package github.kasuminova.ssoptimizer.common.logging;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.util.Locale;

/**
 * 压制原版游戏启动/加载阶段的高频低价值 INFO 日志（资源/规则/脚本加载等），保留
 * WARN/ERROR 与 SSOptimizer 自身日志，避免污染用户检索关键输出（如加载失败的
 * ERROR、警告等）。
 *
 * <p>名单来自 starsector.log 实证统计 + named jar 类路径核对：加载期（SpecLoad 阶段）由
 * {@code com.fs.starfarer.loading.*}/{@code scripts.ScriptStore}/{@code rules.Rules}/
 * {@code com.fs.graphics.TextureLoader}/{@code sound.*}/{@code launcher.ModManager}/
 * {@code codex.CodexTextEntryLoader} 等 logger 打出海量「Loading ...」「Applying data ...」
 * 「Class ... already loaded ... skipping compilation」等单条无信息量日志（单次启动可达
 * 30 万行以上）。注意 {@code util.TextureData} 等仅输出 ERROR 的 logger 不在名单内，
 * 缺失资源的报错必须保留；第三方 mod 的 logger（如 {@code util.ShipColors}）也不在名单内。</p>
 *
 * <p>名单使用运行时 logger 全名（FQCN）：游戏代码经 {@code Logger.getLogger(Class)} 创建
 * logger，log4j-1.2-api 桥接取 {@code Class.getName()}，故 {@code loading.*} 的完整名是
 * {@code com.fs.starfarer.loading.*}（日志中 {@code %c{2}} 截断显示）；仅 {@code sound.*}
 * 因位于 fs.sound_obf 顶层 {@code sound} 包而使用短名。若名单误用截断短名，setLevel 设置的
 * 是另一个无关 logger，过滤在生产运行时不生效（Gradle 单测环境无法暴露——见测试类注释）。</p>
 *
 * <p>机制：直接对噪音 logger {@link Logger#setLevel(Level)} 提高阈值（默认 WARN）。运行时
 * {@code org.apache.log4j} 由 SSOptimizer shade 的 log4j-1.2-api 桥接提供，其
 * {@code setLevel} 经 {@code CategoryUtil.setLevel} 映射到 NanoForge 的 log4j2 运行时，
 * 对后续所有日志判断即时生效；WARN/ERROR 不受影响，SSOptimizer 自身 logger
 * （{@code github.kasuminova.ssoptimizer.*}）不在名单内，同样不受影响。</p>
 *
 * <p>默认开启（WARN）。通过 {@code -Dssoptimizer.logging.vanilla.level=INFO}（或
 * {@code DEBUG}）恢复原版完整加载日志。调用点必须早于原版加载期日志产生——
 * 由 coremod {@code SSOptimizerCorePlugin.onLoad}（早于任何游戏类加载）触发，
 * 而非 {@code SSOptimizerModPlugin.onApplicationLoad}（晚于加载期）。</p>
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
     * 对全部原版加载噪音 logger 应用日志阈值（默认 WARN）。
     * <p>
     * 阈值为 DEBUG 或更低时视为用户显式要求保留完整原版加载日志，不做任何修改
     * （与 LunaLib 阈值语义一致；设 {@code INFO} 时执行 {@code setLevel(INFO)}，
     * 效果等同恢复 INFO 可见）。
     */
    public static void configure() {
        final Level threshold = vanillaThreshold();
        if (threshold.toInt() <= Level.DEBUG_INT) {
            LOGGER.info("[SSOptimizer] Vanilla loading log passthrough enabled at " + threshold);
            return;
        }

        for (String loggerName : NOISE_LOGGER_NAMES) {
            Logger.getLogger(loggerName).setLevel(threshold);
        }
        LOGGER.info("[SSOptimizer] Vanilla loading log threshold enforced at " + threshold
                + " across " + NOISE_LOGGER_NAMES.length + " logger(s)");
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
