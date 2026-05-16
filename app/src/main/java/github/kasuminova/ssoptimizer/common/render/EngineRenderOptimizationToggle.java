package github.kasuminova.ssoptimizer.common.render;

/**
 * 引擎渲染优化开关。
 * <p>
 * 该优化默认关闭，避免在未显式确认的运行环境中替换引擎火焰渲染路径。
 * 需要通过 JVM 参数 {@code -Dssoptimizer.render.engine.enable=true} 显式启用。
 */
public final class EngineRenderOptimizationToggle {
    public static final String ENABLE_PROPERTY = "ssoptimizer.render.engine.enable";

    private EngineRenderOptimizationToggle() {
    }

    /**
     * 返回是否启用引擎渲染优化。
     *
     * @return 仅当 JVM 参数 {@code -Dssoptimizer.render.engine.enable=true} 存在时返回 true
     */
    public static boolean isEnabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }
}