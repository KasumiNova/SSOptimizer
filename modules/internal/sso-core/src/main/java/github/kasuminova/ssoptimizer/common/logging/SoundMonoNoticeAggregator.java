package github.kasuminova.ssoptimizer.common.logging;

import org.apache.log4j.Logger;

import java.util.function.Consumer;

/**
 * {@code sound.Sound} 构造器控制台输出的聚合器。
 *
 * <p>动机：{@code Sound(String, String, InputStream)} 构造器末尾直接
 * {@code System.out.println(String.format("Sound [%s] is NOT mono", id))} /
 * {@code System.out.println(String.format("UI sound [%s] is mono", id))}，
 * 绕过 log4j 只刷控制台（log 文件无记录），加载几百个音效时控制台被逐条刷屏。</p>
 *
 * <p>机制：Mixin 把构造器内两处 {@code PrintStream.println(String)} 调用重定向到
 * {@link #onConstructorPrint(String)}，控制台不再逐条输出；本类按分类计数，每累计
 * {@link #BATCH_SIZE} 条输出一条汇总 INFO（经 log4j，控制台与 log 文件均可见），
 * 加载期结束由 {@link #flushPending()} 输出剩余零头（调用点在
 * ResourceLoaderState.init 返回处，与 RenderThreadMode 加载完成标记同一边界）。</p>
 *
 * <p>实例化与 {@link LoadingNoiseAggregator} 同风格：核心逻辑不依赖日志框架，
 * reporter 由适配层注入（生产为 log4j INFO，测试注入收集器断言）。</p>
 */
public final class SoundMonoNoticeAggregator {
    /** 每个分类累计多少条后输出一次汇总行。 */
    public static final int BATCH_SIZE = 100;

    /** 汇总行输出 logger（SSOptimizer 自身命名空间，不受任何噪音压制影响）。 */
    private static final Logger LOGGER = Logger.getLogger(SoundMonoNoticeAggregator.class);

    /** 生产单例：汇总行经 SSOptimizer 自身 logger 输出。 */
    private static final SoundMonoNoticeAggregator INSTANCE =
            new SoundMonoNoticeAggregator(LOGGER::info);

    /** 「NOT mono」分类自上次汇总以来的待输出计数。 */
    private long pendingNotMono;
    /** 「UI mono」分类自上次汇总以来的待输出计数。 */
    private long pendingUiMono;
    /** 汇总行输出通道。 */
    private final Consumer<String> reporter;

    /** 包级可见：注入 reporter 供单测捕获汇总行。 */
    SoundMonoNoticeAggregator(final Consumer<String> reporter) {
        this.reporter = reporter;
    }

    /**
     * Sound 构造器 println 重定向入口（生产调用点）。
     *
     * @param message 原本要打到控制台的完整消息文本
     */
    public static void onConstructorPrint(final String message) {
        INSTANCE.record(message);
    }

    /** 加载期结束 flush：输出两类剩余的未汇总计数（生产调用点）。 */
    public static void flushPending() {
        INSTANCE.flush();
    }

    /**
     * 记录一条被压制的构造器输出：按分类计数，达到批次阈值输出汇总。
     *
     * @param message 原始 println 消息；null 归入 UI mono 分类兜底计数
     */
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

    /** 输出全部剩余计数为汇总行并清空；计数为零时无输出。 */
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

    /** 汇总行文本（与 SSOptimizer 自身日志统一标识，便于用户检索）。 */
    private static String summary(final long count, final String label) {
        return "[SSOptimizer] " + count + " " + label + " (console notices aggregated)";
    }
}
