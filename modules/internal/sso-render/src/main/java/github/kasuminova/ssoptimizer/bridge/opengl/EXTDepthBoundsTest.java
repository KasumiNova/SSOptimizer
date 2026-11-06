package github.kasuminova.ssoptimizer.bridge.opengl;

public final class EXTDepthBoundsTest {
    private EXTDepthBoundsTest() {
    }

    public static void glDepthBoundsEXT(double p0, double p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTDepthBoundsTest.glDepthBoundsEXT(p0, p1));
    }
}
