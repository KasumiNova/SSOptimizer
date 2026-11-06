package github.kasuminova.ssoptimizer.common.render.tessellation;

public final class ShipMaskTessellationToggle {
    public static final String ENABLE_PROPERTY = "ssoptimizer.render.shipmasktess.enable";

    private ShipMaskTessellationToggle() {
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "true"));
    }
}
