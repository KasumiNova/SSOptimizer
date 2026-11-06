package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

public final class ARBInstancedArrays extends ARBInstancedArraysGen {
    private ARBInstancedArrays() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glVertexAttribDivisorARB(int index, int divisor) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBInstancedArrays.glVertexAttribDivisorARB(index, divisor));
    }
}
