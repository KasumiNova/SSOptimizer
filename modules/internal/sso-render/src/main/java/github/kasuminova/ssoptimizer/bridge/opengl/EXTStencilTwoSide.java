package github.kasuminova.ssoptimizer.bridge.opengl;

public final class EXTStencilTwoSide {
    private EXTStencilTwoSide() {
    }

    public static void glActiveStencilFaceEXT(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTStencilTwoSide.glActiveStencilFaceEXT(p0));
    }
}
