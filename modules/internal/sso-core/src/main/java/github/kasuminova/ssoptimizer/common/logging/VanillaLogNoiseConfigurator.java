package github.kasuminova.ssoptimizer.common.logging;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.util.Locale;

/**
 * 配置原版加载日志的消息级聚合，保留 WARN/ERROR 与未匹配的诊断 INFO。
 * 游戏经 log4j-1.2-api 桥接到 log4j2 时会绕过 1.x Filter，因此聚合过滤器必须安装到 log4j2 root LoggerConfig。
 * 本配置须早于游戏资源加载执行。
 */
public final class VanillaLogNoiseConfigurator {
    public static final String VANILLA_LEVEL_PROPERTY = "ssoptimizer.logging.vanilla.level";

    private static final Logger LOGGER = Logger.getLogger(VanillaLogNoiseConfigurator.class);

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

    private static final java.util.Set<String> NOISE_LOGGERS = java.util.Set.of(NOISE_LOGGER_NAMES);

    static boolean isEngineLoadingLogger(final String name) {
        return name != null && NOISE_LOGGERS.contains(name);
    }

    private VanillaLogNoiseConfigurator() {
    }

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
