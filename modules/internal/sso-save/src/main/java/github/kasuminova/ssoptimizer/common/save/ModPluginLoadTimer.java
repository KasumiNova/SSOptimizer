package github.kasuminova.ssoptimizer.common.save;

import org.apache.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ModPluginLoadTimer {
    public static final String ENABLE_PROPERTY = "ssoptimizer.save.modloadtiming";

    private static final Logger LOGGER = Logger.getLogger(ModPluginLoadTimer.class);
    private static final int TOP_N = 15;

    private static volatile boolean enabled = Boolean.getBoolean(ENABLE_PROPERTY);
    private static final Map<String, long[]> TIMINGS = new LinkedHashMap<>();

    private ModPluginLoadTimer() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void record(final Class<?> pluginClass, final long nanos) {
        TIMINGS.computeIfAbsent(pluginClass.getName(), key -> new long[2])[0] += nanos;
        TIMINGS.get(pluginClass.getName())[1]++;
    }

    public static void dumpAndReset() {
        if (TIMINGS.isEmpty()) {
            return;
        }
        final StringBuilder sb = new StringBuilder("[SSO-Save] onGameLoad timings:");
        TIMINGS.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(TOP_N)
                .forEach(e -> sb.append(String.format(Locale.ROOT, " %.0fms=%s(x%d)",
                        e.getValue()[0] / 1e6, e.getKey(), e.getValue()[1])));
        LOGGER.info(sb.toString());
        TIMINGS.clear();
    }
}
