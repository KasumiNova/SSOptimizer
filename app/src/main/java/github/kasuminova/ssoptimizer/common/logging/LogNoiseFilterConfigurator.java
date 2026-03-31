package github.kasuminova.ssoptimizer.common.logging;

import org.apache.log4j.*;
import org.apache.log4j.spi.Filter;
import org.apache.log4j.spi.LoggingEvent;

import java.util.Enumeration;
import java.util.Locale;

/**
 * Suppresses high-volume LunaLib debug/info chatter while keeping WARN/ERROR visible.
 * Use -Dssoptimizer.logging.lunalib.level=DEBUG to re-enable full LunaLib logs.
 */
public final class LogNoiseFilterConfigurator {
    public static final String LUNALIB_LEVEL_PROPERTY = "ssoptimizer.logging.lunalib.level";

    private static final Logger   LOGGER                  = Logger.getLogger(LogNoiseFilterConfigurator.class);
    private static final String[] LUNALIB_LOGGER_PREFIXES = {
            "lunalib"
    };

    private LogNoiseFilterConfigurator() {
    }

    public static void configure() {
        final Level threshold = lunaLibThreshold();
        enforceLoggerLevels(threshold);

        if (threshold.toInt() <= Level.DEBUG_INT) {
            LOGGER.info("[SSOptimizer] LunaLib logging passthrough enabled at " + threshold);
            return;
        }

        int updatedAppenders = 0;
        final Enumeration<?> appenders = LogManager.getRootLogger().getAllAppenders();
        while (appenders.hasMoreElements()) {
            final Object next = appenders.nextElement();
            if (!(next instanceof AppenderSkeleton appender) || hasNoiseFilter(appender)) {
                continue;
            }
            appender.addFilter(new LunaLibNoiseFilter(threshold));
            updatedAppenders++;
        }

        if (updatedAppenders > 0) {
            LOGGER.info("[SSOptimizer] LunaLib log threshold enforced at " + threshold + " across " + updatedAppenders + " appender(s)");
        }
    }

    static Level lunaLibThreshold() {
        final String configured = System.getProperty(LUNALIB_LEVEL_PROPERTY, "WARN");
        if (configured == null || configured.isBlank()) {
            return Level.WARN;
        }

        final String normalized = configured.trim().toUpperCase(Locale.ROOT);
        if ("TRACE".equals(normalized)) {
            return Level.DEBUG;
        }
        return Level.toLevel(normalized, Level.WARN);
    }

    static boolean shouldSuppress(final String loggerName,
                                  final Level eventLevel,
                                  final Level threshold) {
        if (loggerName == null || loggerName.isBlank() || eventLevel == null || threshold == null) {
            return false;
        }
        if (!isLunaLibLogger(loggerName)) {
            return false;
        }
        return eventLevel.toInt() < threshold.toInt();
    }

    private static boolean isLunaLibLogger(final String loggerName) {
        for (String prefix : LUNALIB_LOGGER_PREFIXES) {
            if (loggerName.equals(prefix) || loggerName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void enforceLoggerLevels(final Level threshold) {
        for (String loggerName : LUNALIB_LOGGER_PREFIXES) {
            Logger.getLogger(loggerName).setLevel(threshold);
        }
        Logger.getLogger("lunalib.backend.ui.settings.LunaSettingsLoader").setLevel(threshold);
        Logger.getLogger("lunalib.lunaSettings.LunaSettings").setLevel(threshold);
    }

    private static boolean hasNoiseFilter(final Appender appender) {
        if (!(appender instanceof AppenderSkeleton skeleton)) {
            return false;
        }

        Filter cursor = skeleton.getFilter();
        while (cursor != null) {
            if (cursor instanceof LunaLibNoiseFilter) {
                return true;
            }
            cursor = cursor.next;
        }
        return false;
    }

    private static final class LunaLibNoiseFilter extends Filter {
        private final Level threshold;

        private LunaLibNoiseFilter(final Level threshold) {
            this.threshold = threshold;
        }

        @Override
        public int decide(final LoggingEvent event) {
            if (event == null) {
                return NEUTRAL;
            }

            if (shouldSuppress(event.getLoggerName(), event.getLevel(), threshold)) {
                return DENY;
            }
            return NEUTRAL;
        }
    }
}