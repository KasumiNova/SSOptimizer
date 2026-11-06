package github.kasuminova.ssoptimizer.bridge.opengl;
public final class EXTBlendFuncSeparate {
    private EXTBlendFuncSeparate() {
    }

    public static void glBlendFuncSeparateEXT(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTBlendFuncSeparate.glBlendFuncSeparateEXT(p0, p1, p2, p3));
    }
}
