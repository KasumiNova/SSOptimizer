package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ATIPnTriangles {
    private ATIPnTriangles() {
    }

    public static void glPNTrianglesfATI(int p0, float p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ATIPnTriangles.glPNTrianglesfATI(p0, p1));
    }

    public static void glPNTrianglesiATI(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ATIPnTriangles.glPNTrianglesiATI(p0, p1));
    }
}
