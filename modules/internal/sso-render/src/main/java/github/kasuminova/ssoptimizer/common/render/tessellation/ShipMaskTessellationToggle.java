package github.kasuminova.ssoptimizer.common.render.tessellation;

/**
 * 舰船形状蒙版三角化优化开关。
 * <p>
 * 该优化默认开启，将 {@code Tesselator.renderTriangles} 的逐帧 GLU 剖分替换为
 * 「耳切三角化 + 按 Bounds 缓存」路径（见 {@link ShipMaskMeshCache}）。
 * 需要排查问题时可通过 JVM 参数 {@code -Dssoptimizer.render.shipmasktess.enable=false}
 * 关停，此时 Mixin 直接走复刻原版的 GLU 降级路径。
 */
public final class ShipMaskTessellationToggle {
    public static final String ENABLE_PROPERTY = "ssoptimizer.render.shipmasktess.enable";

    private ShipMaskTessellationToggle() {
    }

    /**
     * 返回是否启用舰船蒙版三角化优化。
     *
     * @return 默认 true；仅当 JVM 参数显式设为 false 时返回 false
     */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "true"));
    }
}
