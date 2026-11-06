package github.kasuminova.ssoptimizer.bridge.opengl;

public final class EXTProvokingVertex {
    private EXTProvokingVertex() {
    }

    public static void glProvokingVertexEXT(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTProvokingVertex.glProvokingVertexEXT(p0));
    }
}
