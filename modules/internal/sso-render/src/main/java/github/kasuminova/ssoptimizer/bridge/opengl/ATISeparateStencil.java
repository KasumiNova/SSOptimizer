package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ATISeparateStencil {
    private ATISeparateStencil() {
    }

    public static void glStencilFuncSeparateATI(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ATISeparateStencil.glStencilFuncSeparateATI(p0, p1, p2, p3));
    }

    public static void glStencilOpSeparateATI(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ATISeparateStencil.glStencilOpSeparateATI(p0, p1, p2, p3));
    }
}
