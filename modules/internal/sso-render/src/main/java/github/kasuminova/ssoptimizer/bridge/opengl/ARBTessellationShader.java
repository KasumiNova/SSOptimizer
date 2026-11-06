package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBTessellationShader {
    private ARBTessellationShader() {
    }

    public static void glPatchParameter(int p0, java.nio.FloatBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.ARBTessellationShader.glPatchParameter(p0, snapshot.asFloatBuffer()));
    }

    public static void glPatchParameteri(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTessellationShader.glPatchParameteri(p0, p1));
    }
}
