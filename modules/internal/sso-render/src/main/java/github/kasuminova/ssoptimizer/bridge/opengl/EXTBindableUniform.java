package github.kasuminova.ssoptimizer.bridge.opengl;
public final class EXTBindableUniform {
    private EXTBindableUniform() {
    }

    public static int glGetUniformBufferSizeEXT(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.EXTBindableUniform.glGetUniformBufferSizeEXT(p0, p1));
    }

    public static long glGetUniformOffsetEXT(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.EXTBindableUniform.glGetUniformOffsetEXT(p0, p1));
    }

    public static void glUniformBufferEXT(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTBindableUniform.glUniformBufferEXT(p0, p1, p2));
    }
}
