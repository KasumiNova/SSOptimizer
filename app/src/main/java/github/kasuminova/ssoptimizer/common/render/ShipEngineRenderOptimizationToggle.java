package github.kasuminova.ssoptimizer.common.render;

/**
 * 舰船引擎火焰渲染优化开关。
 * <p>
 * 该优化默认关闭，避免在未显式确认的运行环境中替换舰船 {@code Engine.render()} 路径。
 * 需要通过 JVM 参数 {@code -Dssoptimizer.render.shipengine.enable=true} 显式启用。
 */
public final class ShipEngineRenderOptimizationToggle {
    public static final String ENABLE_PROPERTY = "ssoptimizer.render.shipengine.enable";

    private ShipEngineRenderOptimizationToggle() {
    }

    /**
     * 返回是否启用舰船引擎火焰渲染优化。
     *
     * @return 仅当 JVM 参数 {@code -Dssoptimizer.render.shipengine.enable=true} 存在时返回 true
     */
    public static boolean isEnabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }
}