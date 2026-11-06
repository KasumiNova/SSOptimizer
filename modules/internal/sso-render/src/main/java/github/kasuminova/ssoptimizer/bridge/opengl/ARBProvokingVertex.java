package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBProvokingVertex {
    private ARBProvokingVertex() {
    }

    public static void glProvokingVertex(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBProvokingVertex.glProvokingVertex(p0));
    }
}
