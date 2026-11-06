package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBTextureBarrier {
    private ARBTextureBarrier() {
    }

    public static void glTextureBarrier() {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureBarrier.glTextureBarrier());
    }
}
