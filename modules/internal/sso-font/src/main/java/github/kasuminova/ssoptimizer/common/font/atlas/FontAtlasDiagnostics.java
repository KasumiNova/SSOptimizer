package github.kasuminova.ssoptimizer.common.font.atlas;

import org.apache.log4j.Logger;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 动态字形图集的运行时诊断：命中/未命中、页创建/淘汰、上传量计数。
 * 默认关闭，{@code -Dssoptimizer.font.atlas.debug=true} 开启后按固定间隔汇总输出
 * （风格与 {@code TextLayoutDiagnostics} 一致）。
 */
public final class FontAtlasDiagnostics {
    public static final String DEBUG_PROPERTY = "ssoptimizer.font.atlas.debug";

    private static final Logger     LOGGER                  = Logger.getLogger(FontAtlasDiagnostics.class);
    private static final long       LOG_INTERVAL_NANOS      = 5_000_000_000L;

    private static final LongAdder  HITS                = new LongAdder();
    private static final LongAdder  MISSES              = new LongAdder();
    private static final LongAdder  PAGES_CREATED       = new LongAdder();
    private static final LongAdder  PAGES_EVICTED       = new LongAdder();
    private static final LongAdder  UPLOAD_BYTES        = new LongAdder();
    private static final LongAdder  UPLOAD_RECTS        = new LongAdder();
    private static final LongAdder  RASTERIZE_FAILURES  = new LongAdder();
    private static final AtomicLong NEXT_LOG_NANOS      = new AtomicLong();

    private FontAtlasDiagnostics() {
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean(DEBUG_PROPERTY);
    }

    public static void recordHit() {
        if (isEnabled()) {
            HITS.increment();
        }
    }

    public static void recordMiss() {
        if (isEnabled()) {
            MISSES.increment();
        }
    }

    public static void recordPageCreated() {
        if (isEnabled()) {
            PAGES_CREATED.increment();
            maybeLogSummary(System.nanoTime());
        }
    }

    public static void recordPageEvicted() {
        if (isEnabled()) {
            PAGES_EVICTED.increment();
        }
    }

    public static void recordUpload(final long bytes, final int rects) {
        if (isEnabled()) {
            UPLOAD_BYTES.add(bytes);
            UPLOAD_RECTS.add(rects);
        }
    }

    public static void recordRasterizeFailure() {
        if (isEnabled()) {
            RASTERIZE_FAILURES.increment();
        }
    }

    /** 汇总快照（测试与日志共用；未启用时也能取到计数——record 族在关闭时不计数）。 */
    public static String snapshotSummary() {
        return String.format(Locale.ROOT,
                "[SSOptimizer] Font atlas: hits=%d misses=%d pagesCreated=%d pagesEvicted=%d uploadBytes=%d uploadRects=%d rasterizeFailures=%d",
                HITS.sum(), MISSES.sum(), PAGES_CREATED.sum(), PAGES_EVICTED.sum(),
                UPLOAD_BYTES.sum(), UPLOAD_RECTS.sum(), RASTERIZE_FAILURES.sum());
    }

    /** 测试用：清零全部计数，避免用例间静态状态串扰。 */
    public static void resetForTests() {
        HITS.reset();
        MISSES.reset();
        PAGES_CREATED.reset();
        PAGES_EVICTED.reset();
        UPLOAD_BYTES.reset();
        UPLOAD_RECTS.reset();
        RASTERIZE_FAILURES.reset();
        NEXT_LOG_NANOS.set(0L);
    }

    private static void maybeLogSummary(final long now) {
        final long scheduled = NEXT_LOG_NANOS.get();
        if (scheduled != 0L && now < scheduled) {
            return;
        }
        if (!NEXT_LOG_NANOS.compareAndSet(scheduled, now + LOG_INTERVAL_NANOS)) {
            return;
        }
        LOGGER.info(snapshotSummary());
    }
}
