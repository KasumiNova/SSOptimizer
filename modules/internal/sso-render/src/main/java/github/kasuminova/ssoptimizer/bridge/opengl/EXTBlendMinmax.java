package github.kasuminova.ssoptimizer.bridge.opengl;

public final class EXTBlendMinmax {
    private EXTBlendMinmax() {
    }

    public static void glBlendEquationEXT(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTBlendMinmax.glBlendEquationEXT(p0));
    }
}
