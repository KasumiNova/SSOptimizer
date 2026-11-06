package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.common.font.EffectiveScreenScale;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class TextLayoutDiagnostics {
    static final String ENABLE_PROPERTY       = "ssoptimizer.textdiagnostics.enable";
    static final String LOG_INTERVAL_PROPERTY = "ssoptimizer.textdiagnostics.logintervalmillis";

    private static final Logger                                          LOGGER                     = Logger.getLogger(TextLayoutDiagnostics.class);
    private static final int                                             TOP_LIMIT                  = 5;
    private static final AtomicLong                                      NEXT_LOG_NANOS             = new AtomicLong();
    private static final LongAdder                                       V2_RENDER_CALLS            = new LongAdder();
    private static final LongAdder                                       V2_PASSES                  = new LongAdder();
    private static final LongAdder                                       V2_QUADS                   = new LongAdder();
    private static final ConcurrentHashMap<Integer, LongAdder>           REQUESTED_FONT_SIZE_COUNTS = new ConcurrentHashMap<>();

    private TextLayoutDiagnostics() {
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    public static void recordV2Render(final int passCount, final int quadCount, final float fontSize) {
        if (!isEnabled()) {
            return;
        }

        V2_RENDER_CALLS.increment();
        V2_PASSES.add(passCount);
        V2_QUADS.add(quadCount);
        REQUESTED_FONT_SIZE_COUNTS.computeIfAbsent(bucketRequestedFontSizeMillis(fontSize), ignored -> new LongAdder()).increment();
        maybeLogSummary(System.nanoTime());
    }

    static String snapshotSummary() {
        final long v2Calls = V2_RENDER_CALLS.sum();
        if (v2Calls == 0L) {
            return "";
        }

        return String.format(Locale.ROOT,
                "[SSOptimizer] Text layout summary: v2RenderCalls=%d v2Passes=%d v2Quads=%d screenScale=%s requestedFontSizes=%s",
                v2Calls,
                V2_PASSES.sum(),
                V2_QUADS.sum(),
                formatScreenScale(EffectiveScreenScale.current()),
                summarizeIntBuckets(REQUESTED_FONT_SIZE_COUNTS, TextLayoutDiagnostics::formatRequestedFontSizeMillis));
    }

    static void resetForTests() {
        V2_RENDER_CALLS.reset();
        V2_PASSES.reset();
        V2_QUADS.reset();
        NEXT_LOG_NANOS.set(0L);
        REQUESTED_FONT_SIZE_COUNTS.clear();
    }

    private static void maybeLogSummary(final long now) {
        final long intervalMillis = Math.max(0L,
                Long.getLong(LOG_INTERVAL_PROPERTY, 5_000L));
        if (intervalMillis <= 0L) {
            return;
        }

        final long scheduled = NEXT_LOG_NANOS.get();
        if (scheduled != 0L && now < scheduled) {
            return;
        }

        final long next = now + intervalMillis * 1_000_000L;
        if (!NEXT_LOG_NANOS.compareAndSet(scheduled, next)) {
            return;
        }

        final String summary = snapshotSummary();
        if (!summary.isBlank()) {
            LOGGER.info(summary);
        }
    }

    private static String summarizeIntBuckets(final ConcurrentHashMap<Integer, LongAdder> counts,
                                              final java.util.function.IntFunction<String> formatter) {
        if (counts.isEmpty()) {
            return "(none)";
        }

        final List<Map.Entry<Integer, LongAdder>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Comparator.<Map.Entry<Integer, LongAdder>>comparingLong(entry -> entry.getValue().sum())
                              .reversed()
                              .thenComparingInt(Map.Entry::getKey));

        final List<String> top = new ArrayList<>(Math.min(TOP_LIMIT, sorted.size()));
        for (int i = 0; i < sorted.size() && i < TOP_LIMIT; i++) {
            final Map.Entry<Integer, LongAdder> entry = sorted.get(i);
            top.add(formatter.apply(entry.getKey()) + 'x' + entry.getValue().sum());
        }
        return String.join(", ", top);
    }

    private static int bucketRequestedFontSizeMillis(final float requestedFontSize) {
        if (!Float.isFinite(requestedFontSize)) {
            return 0;
        }
        return Math.round(Math.max(0.0f, requestedFontSize) * 1000.0f);
    }

    private static String formatRequestedFontSizeMillis(final int requestedFontSizeMillis) {
        return String.format(Locale.ROOT, "%.3f", requestedFontSizeMillis / 1000.0f);
    }

    private static String formatScreenScale(final float screenScale) {
        if (!Float.isFinite(screenScale)) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.3f", screenScale);
    }
}
