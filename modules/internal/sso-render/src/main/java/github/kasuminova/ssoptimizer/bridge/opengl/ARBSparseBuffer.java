package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBSparseBuffer {
    private ARBSparseBuffer() {
    }

    public static void glBufferPageCommitmentARB(int p0, long p1, long p2, boolean p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSparseBuffer.glBufferPageCommitmentARB(p0, p1, p2, p3));
    }
}
