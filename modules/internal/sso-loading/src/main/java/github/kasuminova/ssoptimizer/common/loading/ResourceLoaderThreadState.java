package github.kasuminova.ssoptimizer.common.loading;

/**
 * 并行资源加载期间按线程隔离 resourcePath 与 suppressCustomResources；清除时必须 remove，避免线程复用残留。
 */
public final class ResourceLoaderThreadState {
    private static final ThreadLocal<String> SOURCE_FILTER = new ThreadLocal<>();

    private static final ThreadLocal<Boolean> SUPPRESS_CUSTOM_RESOURCES = new ThreadLocal<>();

    private ResourceLoaderThreadState() {
    }

    public static String getSourceFilter() {
        return SOURCE_FILTER.get();
    }

    public static void setSourceFilter(final String filter) {
        if (filter == null) {
            SOURCE_FILTER.remove();
        } else {
            SOURCE_FILTER.set(filter);
        }
    }

    public static boolean isSuppressCustomResources() {
        final Boolean value = SUPPRESS_CUSTOM_RESOURCES.get();
        return value != null && value;
    }

    public static void setSuppressCustomResources(final boolean suppress) {
        if (suppress) {
            SUPPRESS_CUSTOM_RESOURCES.set(Boolean.TRUE);
        } else {
            SUPPRESS_CUSTOM_RESOURCES.remove();
        }
    }
}
