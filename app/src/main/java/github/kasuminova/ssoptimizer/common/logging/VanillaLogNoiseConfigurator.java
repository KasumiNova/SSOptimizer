package github.kasuminova.ssoptimizer.common.logging;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.util.Locale;

/**
 * 压制原版游戏启动/加载阶段的高频低价值 INFO 日志（资源/规则/脚本加载等），保留
 * WARN/ERROR 与 SSOptimizer 自身日志，避免污染用户检索关键输出（如加载失败的
 * ERROR、警告等）。
 *
 * <p>名单来自 starsector.log 实证统计：加载期（SpecLoad 阶段）由
 * {@code loading.*}/{@code scripts.ScriptStore}/{@code rules.Rules}/{@code graphics.TextureLoader}/
 * {@code sound.*}/{@code util.ShipColors}/{@code launcher.ModManager}/{@code codex.CodexTextEntryLoader}
 * 等 logger 打出海量「Loading ...」「Applying data ...」「Class ... already loaded ... skipping
 * compilation」等单条无信息量日志（单次启动可达 30 万行以上）。注意 {@code util.TextureData}
 * 等仅输出 ERROR 的 logger 不在名单内，缺失资源的报错必须保留。</p>
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

    /** 原版加载期噪音 logger 全名清单（来自 starsector.log 实证统计，均为 INFO 高频）。 */
    private static final String[] NOISE_LOGGER_NAMES = {
            "loading.LoadingUtils",
            "loading.SpecStore",
            "loading.WeaponSpreadsheetLoader",
            "loading.WeaponSpecLoader",
            "loading.ShipHullSpreadsheetLoader",
            "loading.ShipHullSpecLoader",
            "loading.FighterWingSpreadsheetLoader",
            "loading.HullVariantSpecStore",
            "loading.ShipNameStore",
            "scripts.ScriptStore",
            "scripts.ScriptClassLoader",
            "rules.Rules",
            "graphics.TextureLoader",
            "sound.Sound",
            "sound.Music",
            "util.ShipColors",
            "launcher.ModManager",
            "codex.CodexTextEntryLoader",
    };

    private VanillaLogNoiseConfigurator() {
    }

    /**
     * 对全部原版加载噪音 logger 应用日志阈值（默认 WARN）。
     * <p>
     * 阈值为 INFO 或更低时视为用户显式要求保留完整原版加载日志，不做任何修改。
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
