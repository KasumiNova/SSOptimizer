package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.IntBuffer;

/**
 * org.lwjgl.opengl.GL30 的 bridge 镜像（FBO 核心族）。
 * <p>
 * 与 {@link EXTFramebufferObject}/{@link ARBFramebufferObject} 的 FBO 族同语义
 * （LWJGL2 中三者是同功能的扩展/核心别名），GraphicsLib 运行时三选一，全部
 * 必须覆盖；SSOptimizer 部分助手也用 GL30。阻塞通道语义见
 * {@link EXTFramebufferObject} 类 javadoc。GL30 其余面（VAO/transform feedback
 * 等）本阶段不做。
 */
public final class GL30 {
    private GL30() {
    }

    /**
     * 安装命令消费者，语义同 {@link GL11#install(RenderQueue)}。
     *
     * @param renderQueue 渲染队列实例
     */
    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    /** 测试用：卸载已安装的队列，避免用例间静态状态串扰。 */
    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glBindFramebuffer(int target, int framebuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glBindFramebuffer(target, framebuffer));
    }

    /** 资源分配：阻塞通道取回真实 FBO id。 */
    public static int glGenFramebuffers() {
        return BridgeSupport.blockingGet(org.lwjgl.opengl.GL30::glGenFramebuffers);
    }

    /** 渲染线程直接写入调用方 buffer；调用方阻塞期间 buffer 不被触碰。 */
    public static void glGenFramebuffers(IntBuffer framebuffers) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL30.glGenFramebuffers(framebuffers));
    }

    public static void glDeleteFramebuffers(int framebuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glDeleteFramebuffers(framebuffer));
    }

    public static void glDeleteFramebuffers(IntBuffer framebuffers) {
        BridgeSupport.enqueueSnapshot(framebuffers, snapshot ->
                org.lwjgl.opengl.GL30.glDeleteFramebuffers(snapshot.asIntBuffer()));
    }

    /** 状态查询：阻塞通道取回（FBO 完整性校验语义强依赖执行完成）。 */
    public static int glCheckFramebufferStatus(int target) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL30.glCheckFramebufferStatus(target));
    }

    public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30
                .glFramebufferTexture2D(target, attachment, textarget, texture, level));
    }

    public static void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30
                .glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer));
    }

    public static void glGenerateMipmap(int target) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glGenerateMipmap(target));
    }

    public static void glBindRenderbuffer(int target, int renderbuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glBindRenderbuffer(target, renderbuffer));
    }

    /** 资源分配：阻塞通道取回真实 renderbuffer id。 */
    public static int glGenRenderbuffers() {
        return BridgeSupport.blockingGet(org.lwjgl.opengl.GL30::glGenRenderbuffers);
    }

    /** 渲染线程直接写入调用方 buffer；调用方阻塞期间 buffer 不被触碰。 */
    public static void glGenRenderbuffers(IntBuffer renderbuffers) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL30.glGenRenderbuffers(renderbuffers));
    }

    public static void glDeleteRenderbuffers(int renderbuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glDeleteRenderbuffers(renderbuffer));
    }

    public static void glDeleteRenderbuffers(IntBuffer renderbuffers) {
        BridgeSupport.enqueueSnapshot(renderbuffers, snapshot ->
                org.lwjgl.opengl.GL30.glDeleteRenderbuffers(snapshot.asIntBuffer()));
    }

    public static void glRenderbufferStorage(int target, int internalformat, int width, int height) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glRenderbufferStorage(target, internalformat, width, height));
    }
}
