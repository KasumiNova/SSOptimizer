package github.kasuminova.ssoptimizer.common.logging;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 按消息模式聚合加载期 INFO，保留不匹配的消息及 WARN/ERROR。
 * 非聚合消息或高等级日志到达时输出并清空累计计数；入口以 synchronized 保证多线程计数与输出一致。
 */
public final class LoadingNoiseAggregator {
    public record NoisePattern(Pattern regex, String label) {
        boolean matches(String message) {
            return regex.matcher(message).find();
        }
    }

    static final List<NoisePattern> NOISE_PATTERNS = List.of(
            noise("^Loading JSON from ", "JSON files"),
            noise("^Class .* already loaded", "classes"),
            noise("^Loading rule: ", "rules"),
            noise("^Loading variant ", "variants"),
            noise("^Cleaned buffer for texture ", "texture buffers"),
            noise("^Applying data from [A-Za-z_]+\\.csv to ", "csv data entries"),
            noise("^Loading weapon ", "weapon specs"),
            noise("^Loading ship hull ", "ship hull specs"),
            noise("^Loaded spec with id ", "specs"),
            noise("^Loading sound ", "sounds"),
            noise("^Loading hullmod ", "hullmods"),
            noise("^Loading CSV data from ", "CSV files"),
            noise("^Loading projectile ", "projectile specs"),
            noise("^Loading ship system ", "ship system specs"),
            noise("^Loading wing ", "fighter wings"),
            noise("^Loading skill ", "skills"),
            noise("^Loading hull skin ", "hull skins"),
            noise("^Loading custom campaign entity ", "custom campaign entities"),
            noise("^Loading ability with id ", "abilities"),
            noise("^Loading condition", "conditions"),
            noise("^Loading mission ", "missions"),
            noise("^Loading saved variants ", "saved variants"),
            noise("^Loading terrain", "terrains"),
            noise("^Loading submarket", "submarkets"),
            noise("^Loading intel tag", "intel tags"),
            noise("^Loading ping", "pings"),
            noise("^Loading objective", "objectives"),
            noise("^Loading data/", "data files"),
            noise("^Loading contact tag", "contact tags"),
            noise("^Loading event with ", "events"),
            noise("^Creating streaming player for music with id ", "music streams"),
            noise("^Cleaning up music with id ", "music stream cleanups")
    );

    private static final String REPORT_PREFIX = "[SSOptimizer] Loaded ";

    private final Map<String, Long> pendingCounts = new LinkedHashMap<>();
    private final Consumer<String> reporter;

    public LoadingNoiseAggregator(Consumer<String> reporter) {
        this.reporter = reporter;
    }

    public synchronized boolean decideSuppress(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        for (NoisePattern pattern : NOISE_PATTERNS) {
            if (pattern.matches(message)) {
                pendingCounts.merge(pattern.label(), 1L, Long::sum);
                return true;
            }
        }
        return false;
    }

    public synchronized void flush() {
        if (pendingCounts.isEmpty()) {
            return;
        }
        // 汇总日志会重入本过滤器，必须先快照并清空计数，避免递归输出。
        final Map<String, Long> snapshot = new LinkedHashMap<>(pendingCounts);
        pendingCounts.clear();
        for (Map.Entry<String, Long> entry : snapshot.entrySet()) {
            reporter.accept(REPORT_PREFIX + entry.getValue() + " " + entry.getKey());
        }
    }

    private static NoisePattern noise(String regex, String label) {
        return new NoisePattern(Pattern.compile(regex), label);
    }
}
