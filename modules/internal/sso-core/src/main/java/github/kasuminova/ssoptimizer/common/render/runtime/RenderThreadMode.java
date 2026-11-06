package github.kasuminova.ssoptimizer.common.render.runtime;

/**
 * 渲染线程分离模式的全局开关，属性在进程生命周期内不可变更，读取处可缓存。
 * 默认启用；显式设置 {@code -Dssoptimizer.renderthread.enable=false} 时停用相关字节码改写与桥接。
 */
public final class RenderThreadMode {
    public static final String ENABLE_PROPERTY = "ssoptimizer.renderthread.enable";

    /** 加载期的阻塞式 GL 调用不参与稳态回读熔断。 */
    private static volatile boolean loadingFinished;

    private RenderThreadMode() {
    }

    public static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY, "true"));
    }

    public static void markLoadingFinished() {
        loadingFinished = true;
    }

    public static boolean isLoadingFinished() {
        return loadingFinished;
    }

    public static void resetLoadingFinishedForTesting() {
        loadingFinished = false;
    }
}
