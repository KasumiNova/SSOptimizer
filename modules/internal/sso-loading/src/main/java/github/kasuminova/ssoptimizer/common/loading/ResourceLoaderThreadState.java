package github.kasuminova.ssoptimizer.common.loading;

/**
 * {@code com.fs.util.ResourceLoader} 两块全局可变状态的线程封闭存储。
 * <p>
 * 动机：原版的 {@code resourcePath}（source filter，{@code setResourcePath} 写入、
 * 下次 {@code openResource(String, boolean)} 读取后即置 null）与
 * {@code suppressCustomResources}（{@code StarfarerSettings.loadCSV/loadJSON(path, false)}
 * 置 true、下次 openResource 读取后置 false）都是全局状态，消费语义为「读取即清除」。
 * 原版加载全程单线程，无并发问题；SSOptimizer 的并行加载（SpecLoadScheduler /
 * ImagePreload worker / sound 加载池）引入跨线程干扰——线程 A 设置的 filter/suppress
 * 会泄漏到线程 B 的 openResource，使 B 对大小写正确、文件确实存在的资源抛出
 * not found，或被静默跳过 mod 资源根。<br>
 * 本类把两块状态改为 ThreadLocal 语义：同线程内 set→consume 序列与原版完全一致，
 * 并发下各线程互不可见。字段访问的重定向由
 * {@code ResourceLoaderMixin} / {@code StarfarerSettingsApiImplMixin} 完成。
 */
public final class ResourceLoaderThreadState {
    /** 当前线程的 source filter（null 语义=不过滤，用 remove 表达以避免线程复用残留）。 */
    private static final ThreadLocal<String> SOURCE_FILTER = new ThreadLocal<>();

    /** 当前线程的 suppress 标记（缺省 false，用 remove 表达以避免线程复用残留）。 */
    private static final ThreadLocal<Boolean> SUPPRESS_CUSTOM_RESOURCES = new ThreadLocal<>();

    private ResourceLoaderThreadState() {
    }

    /**
     * 读取当前线程的 source filter（对应原版 GETFIELD {@code resourcePath}）。
     *
     * @return 当前线程设置的 filter；未设置或已消费返回 {@code null}
     */
    public static String getSourceFilter() {
        return SOURCE_FILTER.get();
    }

    /**
     * 写入当前线程的 source filter（对应原版 PUTFIELD {@code resourcePath}，
     * 含 {@code setResourcePath} 的写入与 openResource 消费后的置 null）。
     *
     * @param filter filter 字符串；{@code null} 表示清除
     */
    public static void setSourceFilter(final String filter) {
        if (filter == null) {
            SOURCE_FILTER.remove();
        } else {
            SOURCE_FILTER.set(filter);
        }
    }

    /**
     * 读取当前线程的 suppress 标记（对应原版 GETSTATIC {@code suppressCustomResources}）。
     *
     * @return 当前线程是否处于「只加载 vanilla 资源」状态
     */
    public static boolean isSuppressCustomResources() {
        final Boolean value = SUPPRESS_CUSTOM_RESOURCES.get();
        return value != null && value;
    }

    /**
     * 写入当前线程的 suppress 标记（对应原版 PUTSTATIC {@code suppressCustomResources}，
     * 含 loadCSV/loadJSON 的置 true 与 openResource 消费后的置 false）。
     *
     * @param suppress true=下次 openResource 跳过全部 mod 资源根
     */
    public static void setSuppressCustomResources(final boolean suppress) {
        if (suppress) {
            SUPPRESS_CUSTOM_RESOURCES.set(Boolean.TRUE);
        } else {
            SUPPRESS_CUSTOM_RESOURCES.remove();
        }
    }
}
