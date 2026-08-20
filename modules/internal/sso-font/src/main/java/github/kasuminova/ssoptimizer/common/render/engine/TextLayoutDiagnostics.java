package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.common.font.EffectiveScreenScale;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runtime diagnostics collected before the final glyph quad is emitted.
 * This is the last layer where we still have scale and per-glyph layout
 * identity together, which makes it a practical probe point for phase-2
 * dynamic glyph cache design.
 */
public final class TextLayoutDiagnostics {
    private static final Logger                                          LOGGER                     = Logger.getLogger(TextLayoutDiagnostics.class);
    private static final int                                             TOP_LIMIT                  = 5;
    private static final AtomicLong                                      NEXT_LOG_NANOS             = new AtomicLong();
    private static final LongAdder                                       TOTAL_LAYOUT_GLYPHS        = new LongAdder();
    private static final LongAdder                                       SCALE_MILLIS_SUM           = new LongAdder();
    private static final ConcurrentHashMap<Integer, LongAdder>           SCALE_BUCKET_COUNTS        = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, LongAdder>           REQUESTED_FONT_SIZE_COUNTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, LongAdder>           NOMINAL_FONT_SIZE_COUNTS   = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, LongAdder>           FONT_INSTANCE_COUNTS       = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, LongAdder>           X_ADVANCE_COUNTS           = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, LongAdder>           X_OFFSET_COUNTS            = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<TextGlyphCacheKey, LongAdder> CACHE_KEY_COUNTS           = new ConcurrentHashMap<>();

    private TextLayoutDiagnostics() {
    }

    public static void recordGlyphLayout(final int glyphId,
                                         final int xOffset,
                                         final int xAdvance,
                                         final int fontInstanceId,
                                         final float scale,
                                         final float requestedFontSize,
                                         final int nominalFontSize,
                                         final int lineHeight) {
        if (!Boolean.getBoolean(TextRenderDiagnostics.ENABLE_PROPERTY)) {
            return;
        }

        TOTAL_LAYOUT_GLYPHS.increment();
        SCALE_MILLIS_SUM.add(Math.round(Math.max(0.0f, scale) * 1000.0f));
        SCALE_BUCKET_COUNTS.computeIfAbsent(TextScaleBuckets.bucketScaleMillis(scale), ignored -> new LongAdder()).increment();
        REQUESTED_FONT_SIZE_COUNTS.computeIfAbsent(bucketRequestedFontSizeMillis(requestedFontSize), ignored -> new LongAdder()).increment();
        NOMINAL_FONT_SIZE_COUNTS.computeIfAbsent(nominalFontSize, ignored -> new LongAdder()).increment();
        FONT_INSTANCE_COUNTS.computeIfAbsent(fontInstanceId, ignored -> new LongAdder()).increment();
        X_ADVANCE_COUNTS.computeIfAbsent(xAdvance, ignored -> new LongAdder()).increment();
        X_OFFSET_COUNTS.computeIfAbsent(xOffset, ignored -> new LongAdder()).increment();
        CACHE_KEY_COUNTS.computeIfAbsent(
                TextGlyphCacheKey.fromLayout(fontInstanceId, glyphId, scale, nominalFontSize, lineHeight),
                ignored -> new LongAdder()
        ).increment();
        maybeLogSummary(System.nanoTime());
    }

    static String snapshotSummary() {
        final long total = TOTAL_LAYOUT_GLYPHS.sum();
        if (total == 0L) {
            return "";
        }

        final double averageScale = SCALE_MILLIS_SUM.sum() / 1000.0 / total;
        final float screenScale = currentScreenScale();
        return String.format(Locale.ROOT,
                "[SSOptimizer] Text layout summary: layoutGlyphs=%d uniqueFontInstances=%d screenScale=%s avgScale=%.3f scaleBuckets=%s requestedFontSizes=%s nominalFontSizes=%s topCacheKeys=%s topXAdvances=%s topXOffsets=%s",
                total,
                FONT_INSTANCE_COUNTS.size(),
                formatScreenScale(screenScale),
                averageScale,
                summarizeIntBuckets(SCALE_BUCKET_COUNTS, TextScaleBuckets::bucketLabel),
                summarizeIntBuckets(REQUESTED_FONT_SIZE_COUNTS, TextLayoutDiagnostics::formatRequestedFontSizeMillis),
                summarizeIntBuckets(NOMINAL_FONT_SIZE_COUNTS, String::valueOf),
                summarizeCacheKeys(),
                summarizeIntBuckets(X_ADVANCE_COUNTS, String::valueOf),
                summarizeIntBuckets(X_OFFSET_COUNTS, String::valueOf));
    }

    static void resetForTests() {
        TOTAL_LAYOUT_GLYPHS.reset();
        SCALE_MILLIS_SUM.reset();
        NEXT_LOG_NANOS.set(0L);
        SCALE_BUCKET_COUNTS.clear();
        REQUESTED_FONT_SIZE_COUNTS.clear();
        NOMINAL_FONT_SIZE_COUNTS.clear();
        FONT_INSTANCE_COUNTS.clear();
        X_ADVANCE_COUNTS.clear();
        X_OFFSET_COUNTS.clear();
        CACHE_KEY_COUNTS.clear();
    }

    private static void maybeLogSummary(final long now) {
        final long intervalMillis = Math.max(0L,
                Long.getLong(TextRenderDiagnostics.LOG_INTERVAL_PROPERTY, 5_000L));
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

    private static String summarizeCacheKeys() {
        if (CACHE_KEY_COUNTS.isEmpty()) {
            return "(none)";
        }

        final List<Map.Entry<TextGlyphCacheKey, LongAdder>> sorted = new ArrayList<>(CACHE_KEY_COUNTS.entrySet());
        sorted.sort(Comparator.<Map.Entry<TextGlyphCacheKey, LongAdder>>comparingLong(entry -> entry.getValue().sum())
                              .reversed()
                              .thenComparing(entry -> entry.getKey().summaryLabel()));

        final List<String> top = new ArrayList<>(Math.min(TOP_LIMIT, sorted.size()));
        for (int i = 0; i < sorted.size() && i < TOP_LIMIT; i++) {
            final Map.Entry<TextGlyphCacheKey, LongAdder> entry = sorted.get(i);
            top.add(entry.getKey().summaryLabel() + '=' + entry.getValue().sum());
        }
        return String.join(", ", top);
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

    private static float currentScreenScale() {
        return EffectiveScreenScale.current();
    }

    private static String formatScreenScale(final float screenScale) {
        if (!Float.isFinite(screenScale)) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.3f", screenScale);
    }
}