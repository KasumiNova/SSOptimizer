package github.kasuminova.ssoptimizer.common.render;

/**
 * 舰船引擎火焰渲染优化开关。
 * <p>
 * 该优化默认开启（合批渲染，运行时按 GL 能力自动降级）。
 * 可通过 JVM 参数 {@code -Dssoptimizer.render.shipengine.enable=false} 显式关闭。
 */
public final class ShipEngineRenderOptimizationToggle {
    public static final String ENABLE_PROPERTY = "ssoptimizer.render.shipengine.enable";

    private ShipEngineRenderOptimizationToggle() {
    }

    /**
     * 返回是否启用舰船引擎火焰渲染优化。
     *
     * @return 默认 true，仅当 JVM 参数 {@code -Dssoptimizer.render.shipengine.enable=false} 时返回 false
     */
    public static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY, "true"));
    }
}