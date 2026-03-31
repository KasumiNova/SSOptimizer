package github.kasuminova.ssoptimizer.common.render.engine;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight runtime diagnostics for bitmap text rendering. Disabled by default.
 * When enabled, aggregates glyph-quad usage so we can size a future dynamic glyph
 * cache using real runtime distributions instead of guesswork.
 */
final class TextRenderDiagnostics {
    static final String ENABLE_PROPERTY = "ssoptimizer.textdiagnostics.enable";
    static final String LOG_INTERVAL_PROPERTY = "ssoptimizer.textdiagnostics.logintervalmillis";

    private static final Logger LOGGER = Logger.getLogger(TextRenderDiagnostics.class);
    private static final long DEFAULT_LOG_INTERVAL_MILLIS = 5_000L;
    private static final int TOP_SIGNATURE_LIMIT = 5;

    private static final LongAdder TOTAL_GLYPH_QUADS = new LongAdder();
    private static final LongAdder TOTAL_SHADOW_PASSES = new LongAdder();
    private static final LongAdder NATIVE_GLYPH_QUADS = new LongAdder();
    private static final LongAdder JAVA_GLYPH_QUADS = new LongAdder();
    private static final LongAdder SCALED_GLYPH_QUADS = new LongAdder();
    private static final LongAdder MICRO_GLYPH_QUADS = new LongAdder();
    private static final LongAdder SCALE_MILLIS_SUM = new LongAdder();
    private static final AtomicLong MAX_SHADOW_COPIES = new AtomicLong();
    private static final AtomicLong NEXT_LOG_NANOS = new AtomicLong();
    private static final ConcurrentHashMap<String, LongAdder> SIGNATURE_COUNTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, LongAdder> SCALE_BUCKET_COUNTS = new ConcurrentHashMap<>();

    private TextRenderDiagnostics() {
    }

    static void recordGlyphQuad(final int glyphWidth,
                                final int glyphHeight,
                                final int bearingY,
                                final float texWidth,
                                final float texHeight,
                                final float scale,
                                final int shadowCopies,
                                final boolean nativePath) {
        if (!isEnabled()) {
            return;
        }

        TOTAL_GLYPH_QUADS.increment();
        TOTAL_SHADOW_PASSES.add(Math.max(0, shadowCopies));
        SCALE_MILLIS_SUM.add(Math.round(Math.max(0.0f, scale) * 1000.0f));
        SCALE_BUCKET_COUNTS.computeIfAbsent(TextScaleBuckets.bucketScaleMillis(scale), ignored -> new LongAdder()).increment();

        if (nativePath) {
            NATIVE_GLYPH_QUADS.increment();
        } else {
            JAVA_GLYPH_QUADS.increment();
        }

        if (!TextScaleBuckets.isIdentityScale(scale)) {
            SCALED_GLYPH_QUADS.increment();
        }

        if (glyphWidth <= 8 || glyphHeight <= 8) {
            MICRO_GLYPH_QUADS.increment();
        }

        updateMaxShadowCopies(shadowCopies);
        SIGNATURE_COUNTS.computeIfAbsent(signatureOf(glyphWidth, glyphHeight, bearingY, texWidth, texHeight, scale, shadowCopies),
                ignored -> new LongAdder()).increment();
        maybeLogSummary(System.nanoTime());
    }

    static String snapshotSummary() {
        final long totalGlyphs = TOTAL_GLYPH_QUADS.sum();
        if (totalGlyphs == 0L) {
            return "";
        }

        final long shadowPasses = TOTAL_SHADOW_PASSES.sum();
        final long nativeGlyphs = NATIVE_GLYPH_QUADS.sum();
        final long javaGlyphs = JAVA_GLYPH_QUADS.sum();
        final long scaledGlyphs = SCALED_GLYPH_QUADS.sum();
        final long microGlyphs = MICRO_GLYPH_QUADS.sum();
        final double averageScale = SCALE_MILLIS_SUM.sum() / 1000.0 / totalGlyphs;

        return String.format(Locale.ROOT,
            "[SSOptimizer] Text render summary: glyphQuads=%d native=%d java=%d shadowPasses=%d avgScale=%.3f scaledGlyphs=%d microGlyphs=%d maxShadowCopies=%d scaleBuckets=%s topGlyphSignatures=%s",
                totalGlyphs,
                nativeGlyphs,
                javaGlyphs,
                shadowPasses,
                averageScale,
                scaledGlyphs,
                microGlyphs,
                MAX_SHADOW_COPIES.get(),
            summarizeScaleBuckets(),
                summarizeTopSignatures());
    }

    static void resetForTests() {
        TOTAL_GLYPH_QUADS.reset();
        TOTAL_SHADOW_PASSES.reset();
        NATIVE_GLYPH_QUADS.reset();
        JAVA_GLYPH_QUADS.reset();
        SCALED_GLYPH_QUADS.reset();
        MICRO_GLYPH_QUADS.reset();
        SCALE_MILLIS_SUM.reset();
        MAX_SHADOW_COPIES.set(0L);
        NEXT_LOG_NANOS.set(0L);
        SIGNATURE_COUNTS.clear();
        SCALE_BUCKET_COUNTS.clear();
    }

    private static boolean isEnabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    private static long logIntervalMillis() {
        return Math.max(0L, Long.getLong(LOG_INTERVAL_PROPERTY, DEFAULT_LOG_INTERVAL_MILLIS));
    }

    private static void maybeLogSummary(final long now) {
        final long intervalMillis = logIntervalMillis();
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

    private static void updateMaxShadowCopies(final int shadowCopies) {
        final long normalized = Math.max(0, shadowCopies);
        long current = MAX_SHADOW_COPIES.get();
        while (normalized > current && !MAX_SHADOW_COPIES.compareAndSet(current, normalized)) {
            current = MAX_SHADOW_COPIES.get();
        }
    }

    private static String signatureOf(final int glyphWidth,
                                      final int glyphHeight,
                                      final int bearingY,
                                      final float texWidth,
                                      final float texHeight,
                                      final float scale,
                                      final int shadowCopies) {
        return glyphWidth + "x" + glyphHeight
                + "/b" + bearingY
                + "/uv" + quantizeTexture(texWidth) + 'x' + quantizeTexture(texHeight)
            + "/s" + TextScaleBuckets.bucketScaleMillis(scale)
                + "/sh" + Math.max(0, shadowCopies);
    }

    private static int quantizeTexture(final float texDimension) {
        return Math.max(0, Math.round(texDimension * 10_000.0f));
    }

    private static String summarizeTopSignatures() {
        if (SIGNATURE_COUNTS.isEmpty()) {
            return "(none)";
        }

        final List<Map.Entry<String, LongAdder>> sorted = new ArrayList<>(SIGNATURE_COUNTS.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, LongAdder>>comparingLong(entry -> entry.getValue().sum())
                .reversed()
                .thenComparing(Map.Entry::getKey));

        final List<String> top = new ArrayList<>(Math.min(TOP_SIGNATURE_LIMIT, sorted.size()));
        for (int i = 0; i < sorted.size() && i < TOP_SIGNATURE_LIMIT; i++) {
            final Map.Entry<String, LongAdder> entry = sorted.get(i);
            top.add(entry.getKey() + '=' + entry.getValue().sum());
        }
        return String.join(", ", top);
    }

    private static String summarizeScaleBuckets() {
        if (SCALE_BUCKET_COUNTS.isEmpty()) {
            return "(none)";
        }

        final List<Map.Entry<Integer, LongAdder>> sorted = new ArrayList<>(SCALE_BUCKET_COUNTS.entrySet());
        sorted.sort(Comparator.<Map.Entry<Integer, LongAdder>>comparingLong(entry -> entry.getValue().sum())
                .reversed()
                .thenComparingInt(Map.Entry::getKey));

        final List<String> top = new ArrayList<>(Math.min(4, sorted.size()));
        for (int i = 0; i < sorted.size() && i < 4; i++) {
            final Map.Entry<Integer, LongAdder> entry = sorted.get(i);
            top.add(TextScaleBuckets.bucketLabel(entry.getKey()) + 'x' + entry.getValue().sum());
        }
        return String.join(", ", top);
    }
}