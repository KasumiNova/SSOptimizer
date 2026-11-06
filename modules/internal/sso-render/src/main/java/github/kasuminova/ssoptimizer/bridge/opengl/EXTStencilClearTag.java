package github.kasuminova.ssoptimizer.bridge.opengl;

public final class EXTStencilClearTag {
    private EXTStencilClearTag() {
    }

    public static void glStencilClearTagEXT(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTStencilClearTag.glStencilClearTagEXT(p0, p1));
    }
}
