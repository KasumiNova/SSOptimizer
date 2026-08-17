package github.kasuminova.ssoptimizer.common.render.runtime;

/**
 * 渲染线程分离模式的全局开关。
 * <p>
 * 动机：分离模式（{@code -Dssoptimizer.renderthread.enable=true}）下 GL 执行被迁移到
 * 独立渲染线程，主线程只做逻辑与命令录制（架构见
 * docs/design/render-logic-separation-entrypoints.md）。该模式影响面广且各处需要在
 * 类初始化期一次性决策（ASM transformer 是否改写、native GL 是否初始化、各渲染助手
 * 是否降级），故收敛为单一事实源。
 * <p>
 * 默认 false：关闭时 transformer 完全 no-op、bridge 不安装、native 路径不变，
 * 对默认运行路径零影响。属性在进程生命周期内不允许变更（onLoad 期的装配决策不可撤销），
 * 读取处可自行缓存。
 */
public final class RenderThreadMode {
    /** 分离模式开关系统属性名。 */
    public static final String ENABLE_PROPERTY = "ssoptimizer.renderthread.enable";

    /**
     * 游戏资源加载期（{@code ResourceLoaderState.init}）是否已结束。
     * 用途：RenderQueueImpl 的 StallDetector 熔断门控——加载期推进画面本身就在
     * 渲染帧，纹理/字体/shader 的成批一次性分配会产生大量阻塞式 GL 调用，
     * 属正常形态；熔断只针对加载结束后的稳态逐帧 getter 回读。
     */
    private static volatile boolean loadingFinished;

    private RenderThreadMode() {
    }

    /**
     * @return 渲染线程分离模式是否启用
     */
    public static boolean isEnabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    /** 标记资源加载期结束（ResourceLoaderStateMixin 在 init RETURN 时调用）。幂等。 */
    public static void markLoadingFinished() {
        loadingFinished = true;
    }

    /**
     * @return 资源加载期是否已结束（false 时阻塞式 GL 调用不计入熔断窗口）
     */
    public static boolean isLoadingFinished() {
        return loadingFinished;
    }

    /** 测试用：复位加载期标记，避免用例间静态状态串扰。 */
    public static void resetLoadingFinishedForTesting() {
        loadingFinished = false;
    }
}
