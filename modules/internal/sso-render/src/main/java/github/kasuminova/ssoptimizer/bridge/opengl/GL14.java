package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

public final class GL14 extends GL14Gen {
    private GL14() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glBlendEquation(int mode) {
        BridgeSupport.simulatedState().onBlendEquation(mode);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glBlendEquation(mode));
    }

    public static void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL14.glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha));
    }

    public static void glMultiDrawArrays(int mode, java.nio.IntBuffer firsts, java.nio.IntBuffer counts) {
        java.nio.ByteBuffer firstsSnapshot = BridgeSupport.pool().snapshot(firsts);
        java.nio.ByteBuffer countsSnapshot = BridgeSupport.pool().snapshot(counts);
        BridgeSupport.enqueue(() -> {
            try {
                org.lwjgl.opengl.GL14.glMultiDrawArrays(mode, firstsSnapshot.asIntBuffer(),
                        countsSnapshot.asIntBuffer());
            } finally {
                BridgeSupport.releaseSnapshot(firstsSnapshot);
                BridgeSupport.releaseSnapshot(countsSnapshot);
            }
        });
    }
}
