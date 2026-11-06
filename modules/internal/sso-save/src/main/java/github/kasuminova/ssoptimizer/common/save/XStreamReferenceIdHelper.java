package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.core.SequenceGenerator;
import github.kasuminova.ssoptimizer.mixin.save.XStreamSequenceGeneratorAccessor;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class XStreamReferenceIdHelper {
    static final String MAX_PRECOMPUTED_REFERENCE_ID_PROPERTY = "ssoptimizer.save.referenceid.cache.max";

    private static final int REFERENCE_ID_CHUNK_SHIFT = 16;
    private static final int REFERENCE_ID_CHUNK_SIZE = 1 << REFERENCE_ID_CHUNK_SHIFT;
    private static final int REFERENCE_ID_CHUNK_MASK = REFERENCE_ID_CHUNK_SIZE - 1;
    private static final int DEFAULT_MAX_PRECOMPUTED_REFERENCE_ID_LIMIT = 1 << 20;
    private static final int PROACTIVE_WARMUP_REMAINING_THRESHOLD = REFERENCE_ID_CHUNK_SIZE >>> 2;

    private static final char[] DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final AtomicInteger REQUESTED_WARMUP_LIMIT = new AtomicInteger(REFERENCE_ID_CHUNK_SIZE);
    private static final AtomicBoolean WARMUP_RUNNING = new AtomicBoolean(false);

    private static volatile String[][] precomputedReferenceIdChunks = buildInitialPrecomputedChunks();
    private static volatile int precomputedReferenceIdLimit = REFERENCE_ID_CHUNK_SIZE;
    private static volatile CompletableFuture<Void> warmupFuture;

    private static final Class<SequenceGenerator> SEQUENCE_GENERATOR_CLASS = SequenceGenerator.class;

    private XStreamReferenceIdHelper() {
    }

    public static boolean supportsOptimizedIds(final Object generator) {
        return generator != null && generator.getClass() == SEQUENCE_GENERATOR_CLASS;
    }

    public static int readSequenceCounter(final Object generator) {
        if (!supportsOptimizedIds(generator)) {
            throw new IllegalArgumentException("Unsupported XStream ID generator: " + generator);
        }
        return ((XStreamSequenceGeneratorAccessor) generator).ssoptimizer$getCounter();
    }

    public static String nextReferenceId(final int value) {
        return toCompactString(value);
    }

    static String toCompactString(final int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Reference id must be non-negative: " + value);
        }

        final String cached = lookupPrecomputedReferenceId(value);
        if (cached != null) {
            if (value >= precomputedReferenceIdLimit - PROACTIVE_WARMUP_REMAINING_THRESHOLD) {
                requestAdaptiveWarmup(value + REFERENCE_ID_CHUNK_SIZE);
            }
            return cached;
        }

        requestAdaptiveWarmup(value);
        return toCompactStringUncached(value);
    }

    static String toCompactStringUncached(final int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Reference id must be non-negative: " + value);
        }
        if (value == 0) {
            return "0";
        }

        final char[] buffer = new char[7];
        int current = value;
        int cursor = buffer.length;
        while (current != 0) {
            final int digit = current % DIGITS.length;
            buffer[--cursor] = DIGITS[digit];
            current /= DIGITS.length;
        }
        return new String(buffer, cursor, buffer.length - cursor);
    }

    static void resetAdaptiveCacheForTests() {
        REQUESTED_WARMUP_LIMIT.set(REFERENCE_ID_CHUNK_SIZE);
        precomputedReferenceIdChunks = buildInitialPrecomputedChunks();
        precomputedReferenceIdLimit = REFERENCE_ID_CHUNK_SIZE;
        warmupFuture = null;
        WARMUP_RUNNING.set(false);
    }

    static void requestWarmupToForTests(final int maxValue) {
        requestAdaptiveWarmup(maxValue);
    }

    static boolean awaitWarmup(final long timeoutMillis) {
        final CompletableFuture<Void> future = warmupFuture;
        if (future == null) {
            return true;
        }
        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (final TimeoutException e) {
            return false;
        } catch (final ExecutionException e) {
            throw new IllegalStateException("XStream 引用 ID 预热任务执行失败", e.getCause());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 XStream 引用 ID 预热时被中断", e);
        }
    }

    static int cachedReferenceIdUpperBound() {
        return precomputedReferenceIdLimit - 1;
    }

    private static void requestAdaptiveWarmup(final int maxValue) {
        final int targetLimit = clampWarmupLimit(roundUpToChunkBoundary(maxValue + 1));
        if (targetLimit <= precomputedReferenceIdLimit) {
            return;
        }

        REQUESTED_WARMUP_LIMIT.accumulateAndGet(targetLimit, Math::max);
        startWarmupIfNeeded();
    }

    private static void startWarmupIfNeeded() {
        if (!WARMUP_RUNNING.compareAndSet(false, true)) {
            return;
        }

        warmupFuture = CompletableFuture.runAsync(() -> {
            try {
                warmupRequestedChunks();
            } finally {
                WARMUP_RUNNING.set(false);
                if (REQUESTED_WARMUP_LIMIT.get() > precomputedReferenceIdLimit) {
                    startWarmupIfNeeded();
                }
            }
        });
    }

    private static void warmupRequestedChunks() {
        while (true) {
            final int requestedLimit = REQUESTED_WARMUP_LIMIT.get();
            final int currentLimit = precomputedReferenceIdLimit;
            if (currentLimit >= requestedLimit) {
                return;
            }

            final int nextChunkIndex = currentLimit >>> REFERENCE_ID_CHUNK_SHIFT;
            final String[] nextChunk = buildReferenceIdChunk(nextChunkIndex);

            synchronized (XStreamReferenceIdHelper.class) {
                if (precomputedReferenceIdLimit != currentLimit) {
                    continue;
                }

                final String[][] currentChunks = precomputedReferenceIdChunks;
                final String[][] expanded = Arrays.copyOf(currentChunks, nextChunkIndex + 1);
                expanded[nextChunkIndex] = nextChunk;
                precomputedReferenceIdChunks = expanded;
                precomputedReferenceIdLimit = (nextChunkIndex + 1) * REFERENCE_ID_CHUNK_SIZE;
            }
        }
    }

    private static int clampWarmupLimit(final int requestedLimit) {
        return Math.max(REFERENCE_ID_CHUNK_SIZE,
                Math.min(requestedLimit, configuredMaximumPrecomputedReferenceIdLimit()));
    }

    private static int configuredMaximumPrecomputedReferenceIdLimit() {
        final int configured = Integer.getInteger(MAX_PRECOMPUTED_REFERENCE_ID_PROPERTY,
                DEFAULT_MAX_PRECOMPUTED_REFERENCE_ID_LIMIT);
        return roundUpToChunkBoundary(Math.max(REFERENCE_ID_CHUNK_SIZE, configured));
    }

    private static int roundUpToChunkBoundary(final int value) {
        if (value <= REFERENCE_ID_CHUNK_SIZE) {
            return REFERENCE_ID_CHUNK_SIZE;
        }

        final long rounded = ((long) value + REFERENCE_ID_CHUNK_SIZE - 1L) & ~((long) REFERENCE_ID_CHUNK_MASK);
        return rounded >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rounded;
    }

    private static String lookupPrecomputedReferenceId(final int value) {
        final String[][] chunks = precomputedReferenceIdChunks;
        final int chunkIndex = value >>> REFERENCE_ID_CHUNK_SHIFT;
        if (chunkIndex >= chunks.length) {
            return null;
        }

        final String[] chunk = chunks[chunkIndex];
        return chunk == null ? null : chunk[value & REFERENCE_ID_CHUNK_MASK];
    }

    private static String[][] buildInitialPrecomputedChunks() {
        return new String[][]{buildReferenceIdChunk(0)};
    }

    private static String[] buildReferenceIdChunk(final int chunkIndex) {
        final int startValue = chunkIndex * REFERENCE_ID_CHUNK_SIZE;
        final String[] chunk = new String[REFERENCE_ID_CHUNK_SIZE];
        for (int offset = 0; offset < chunk.length; offset++) {
            chunk[offset] = toCompactStringUncached(startValue + offset);
        }
        return chunk;
    }
}
