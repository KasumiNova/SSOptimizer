package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.IntBuffer;

public final class ARBFramebufferObject extends ARBFramebufferObjectGen {
    private ARBFramebufferObject() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glBindFramebuffer(int target, int framebuffer) {
        if (target == org.lwjgl.opengl.ARBFramebufferObject.GL_FRAMEBUFFER) {
            BridgeSupport.setFramebufferBinding(framebuffer);
        }
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBFramebufferObject.glBindFramebuffer(target, framebuffer));
    }

    public static int glGenFramebuffers() {
        return BridgeSupport.blockingGetResource(org.lwjgl.opengl.ARBFramebufferObject::glGenFramebuffers);
    }

    public static void glGenFramebuffers(IntBuffer framebuffers) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.ARBFramebufferObject.glGenFramebuffers(framebuffers));
    }

    public static void glDeleteFramebuffers(int framebuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBFramebufferObject.glDeleteFramebuffers(framebuffer));
    }

    public static void glDeleteFramebuffers(IntBuffer framebuffers) {
        BridgeSupport.enqueueSnapshot(framebuffers, snapshot ->
                org.lwjgl.opengl.ARBFramebufferObject.glDeleteFramebuffers(snapshot.asIntBuffer()));
    }

    public static int glCheckFramebufferStatus(int target) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBFramebufferObject.glCheckFramebufferStatus(target));
    }

    public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBFramebufferObject
                .glFramebufferTexture2D(target, attachment, textarget, texture, level));
    }

    public static void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBFramebufferObject
                .glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer));
    }

    public static void glGenerateMipmap(int target) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBFramebufferObject.glGenerateMipmap(target));
    }

    public static void glBindRenderbuffer(int target, int renderbuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBFramebufferObject.glBindRenderbuffer(target, renderbuffer));
    }

    public static int glGenRenderbuffers() {
        return BridgeSupport.blockingGetResource(org.lwjgl.opengl.ARBFramebufferObject::glGenRenderbuffers);
    }

    public static void glGenRenderbuffers(IntBuffer renderbuffers) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.ARBFramebufferObject.glGenRenderbuffers(renderbuffers));
    }

    public static void glDeleteRenderbuffers(int renderbuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBFramebufferObject.glDeleteRenderbuffers(renderbuffer));
    }

    public static void glDeleteRenderbuffers(IntBuffer renderbuffers) {
        BridgeSupport.enqueueSnapshot(renderbuffers, snapshot ->
                org.lwjgl.opengl.ARBFramebufferObject.glDeleteRenderbuffers(snapshot.asIntBuffer()));
    }

    public static void glRenderbufferStorage(int target, int internalformat, int width, int height) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBFramebufferObject
                .glRenderbufferStorage(target, internalformat, width, height));
    }


    public static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                         int dstX0, int dstY0, int dstX1, int dstY1,
                                         int mask, int filter) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBFramebufferObject.glBlitFramebuffer(
                srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter));
    }

    public static void glFramebufferTexture1D(int target, int attachment, int textarget, int texture, int level) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBFramebufferObject
                .glFramebufferTexture1D(target, attachment, textarget, texture, level));
    }

    public static void glFramebufferTexture3D(int target, int attachment, int textarget, int texture,
                                              int level, int layer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBFramebufferObject
                .glFramebufferTexture3D(target, attachment, textarget, texture, level, layer));
    }

    public static void glFramebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBFramebufferObject
                .glFramebufferTextureLayer(target, attachment, texture, level, layer));
    }

    public static void glRenderbufferStorageMultisample(int target, int samples, int internalformat,
                                                        int width, int height) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBFramebufferObject
                .glRenderbufferStorageMultisample(target, samples, internalformat, width, height));
    }
}
