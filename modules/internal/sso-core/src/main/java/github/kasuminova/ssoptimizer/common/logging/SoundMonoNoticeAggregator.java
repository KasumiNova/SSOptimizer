package github.kasuminova.ssoptimizer.common.logging;

import org.apache.log4j.Logger;

import java.util.function.Consumer;

/**
 * 聚合 {@code sound.Sound} 构造器的控制台消息，按分类计数输出汇总 INFO。
 * 计数状态由同步方法保护，加载结束时必须调用 {@link #flushPending()} 输出剩余计数。
 */
public final class SoundMonoNoticeAggregator {
    public static final int BATCH_SIZE = 100;

    private static final Logger LOGGER = Logger.getLogger(SoundMonoNoticeAggregator.class);

    private static final SoundMonoNoticeAggregator INSTANCE =
            new SoundMonoNoticeAggregator(LOGGER::info);

    private long pendingNotMono;
    private long pendingUiMono;
    private final Consumer<String> reporter;

    SoundMonoNoticeAggregator(final Consumer<String> reporter) {
        this.reporter = reporter;
    }

    public static void onConstructorPrint(final String message) {
        INSTANCE.record(message);
    }

    public static void flushPending() {
        INSTANCE.flush();
    }

    synchronized void record(final String message) {
        if (message != null && message.contains("is NOT mono")) {
            pendingNotMono++;
            if (pendingNotMono >= BATCH_SIZE) {
                reporter.accept(summary(pendingNotMono, "sounds are NOT mono"));
                pendingNotMono = 0;
            }
        } else {
            pendingUiMono++;
            if (pendingUiMono >= BATCH_SIZE) {
                reporter.accept(summary(pendingUiMono, "UI sounds are mono"));
                pendingUiMono = 0;
            }
        }
    }

    synchronized void flush() {
        if (pendingNotMono > 0) {
            reporter.accept(summary(pendingNotMono, "sounds are NOT mono"));
            pendingNotMono = 0;
        }
        if (pendingUiMono > 0) {
            reporter.accept(summary(pendingUiMono, "UI sounds are mono"));
            pendingUiMono = 0;
        }
    }

    private static String summary(final long count, final String label) {
        return "[SSOptimizer] " + count + " " + label + " (console notices aggregated)";
    }
}
