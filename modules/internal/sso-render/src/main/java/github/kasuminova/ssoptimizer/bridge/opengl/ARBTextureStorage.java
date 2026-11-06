package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

public final class ARBTextureStorage extends ARBTextureStorageGen {
    private ARBTextureStorage() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glTexStorage2D(int target, int levels, int internalFormat, int width, int height) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureStorage
                .glTexStorage2D(target, levels, internalFormat, width, height));
    }
}
