package github.kasuminova.ssoptimizer.common.render;

/** 控制舰船引擎火焰合批渲染，默认启用，可通过系统属性显式关闭。 */
public final class ShipEngineRenderOptimizationToggle {
    public static final String ENABLE_PROPERTY = "ssoptimizer.render.shipengine.enable";

    private ShipEngineRenderOptimizationToggle() {
    }

    public static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY, "true"));
    }
}