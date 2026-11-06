package github.kasuminova.ssoptimizer.common.render;

public final class ShipEngineRenderToggle {
    public static final String ENABLE_PROPERTY = "ssoptimizer.render.shipengine.enable";

    private ShipEngineRenderToggle() {
    }

    public static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY, "true"));
    }
}