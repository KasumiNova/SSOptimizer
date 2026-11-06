package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.IntBuffer;

public final class EXTFramebufferObject extends EXTFramebufferObjectGen {
    private EXTFramebufferObject() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glBindFramebufferEXT(int target, int framebuffer) {
        if (target == org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_EXT) {
            // 录制侧同步跟踪 FRAMEBUFFER 绑定，供 GL11.glGetInteger 无阻塞读取。
            BridgeSupport.setFramebufferBinding(framebuffer);
        }
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject.glBindFramebufferEXT(target, framebuffer));
    }

    public static int glGenFramebuffersEXT() {
        return BridgeSupport.blockingGetResource(org.lwjgl.opengl.EXTFramebufferObject::glGenFramebuffersEXT);
    }

    public static void glGenFramebuffersEXT(IntBuffer framebuffers) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.EXTFramebufferObject.glGenFramebuffersEXT(framebuffers));
    }

    public static void glDeleteFramebuffersEXT(int framebuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject.glDeleteFramebuffersEXT(framebuffer));
    }

    public static void glDeleteFramebuffersEXT(IntBuffer framebuffers) {
        BridgeSupport.enqueueSnapshot(framebuffers, snapshot ->
                org.lwjgl.opengl.EXTFramebufferObject.glDeleteFramebuffersEXT(snapshot.asIntBuffer()));
    }

    public static int glCheckFramebufferStatusEXT(int target) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.EXTFramebufferObject.glCheckFramebufferStatusEXT(target));
    }

    public static void glFramebufferTexture2DEXT(int target, int attachment, int textarget, int texture, int level) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject
                .glFramebufferTexture2DEXT(target, attachment, textarget, texture, level));
    }

    public static void glFramebufferRenderbufferEXT(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject
                .glFramebufferRenderbufferEXT(target, attachment, renderbuffertarget, renderbuffer));
    }

    public static void glGenerateMipmapEXT(int target) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject.glGenerateMipmapEXT(target));
    }

    public static void glBindRenderbufferEXT(int target, int renderbuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject.glBindRenderbufferEXT(target, renderbuffer));
    }

    public static int glGenRenderbuffersEXT() {
        return BridgeSupport.blockingGetResource(org.lwjgl.opengl.EXTFramebufferObject::glGenRenderbuffersEXT);
    }

    public static void glGenRenderbuffersEXT(IntBuffer renderbuffers) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.EXTFramebufferObject.glGenRenderbuffersEXT(renderbuffers));
    }

    public static void glDeleteRenderbuffersEXT(int renderbuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject.glDeleteRenderbuffersEXT(renderbuffer));
    }

    public static void glDeleteRenderbuffersEXT(IntBuffer renderbuffers) {
        BridgeSupport.enqueueSnapshot(renderbuffers, snapshot ->
                org.lwjgl.opengl.EXTFramebufferObject.glDeleteRenderbuffersEXT(snapshot.asIntBuffer()));
    }

    public static void glRenderbufferStorageEXT(int target, int internalformat, int width, int height) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject
                .glRenderbufferStorageEXT(target, internalformat, width, height));
    }


    public static void glFramebufferTexture1DEXT(int target, int attachment, int textarget, int texture, int level) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject
                .glFramebufferTexture1DEXT(target, attachment, textarget, texture, level));
    }

    public static void glFramebufferTexture3DEXT(int target, int attachment, int textarget, int texture,
                                                 int level, int layer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject
                .glFramebufferTexture3DEXT(target, attachment, textarget, texture, level, layer));
    }
}
