package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
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
        if (target == org.lwjgl.opengl.GL30.GL_FRAMEBUFFER) {
            // 同 EXTFramebufferObject.glBindFramebufferEXT：录制侧 FRAMEBUFFER 绑定跟踪
            BridgeSupport.setFramebufferBinding(framebuffer);
        }
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glBindFramebuffer(target, framebuffer));
    }

    /** 资源分配：阻塞通道取回真实 FBO id。 */
    public static int glGenFramebuffers() {
        return BridgeSupport.blockingGetResource(org.lwjgl.opengl.GL30::glGenFramebuffers);
    }

    /** 渲染线程直接写入调用方 buffer；调用方阻塞期间 buffer 不被触碰。 */
    public static void glGenFramebuffers(IntBuffer framebuffers) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.GL30.glGenFramebuffers(framebuffers));
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
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL30.glCheckFramebufferStatus(target));
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
        return BridgeSupport.blockingGetResource(org.lwjgl.opengl.GL30::glGenRenderbuffers);
    }

    /** 渲染线程直接写入调用方 buffer；调用方阻塞期间 buffer 不被触碰。 */
    public static void glGenRenderbuffers(IntBuffer renderbuffers) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.GL30.glGenRenderbuffers(renderbuffers));
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

    // ------------------------------------------------------------------
    // 盘点补面：模组实际使用的 VAO / blit / map 族
    // ------------------------------------------------------------------

    /** FBO 间 blit（模组离屏合成路径）。 */
    public static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                         int dstX0, int dstY0, int dstX1, int dstY1,
                                         int mask, int filter) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glBlitFramebuffer(
                srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter));
    }

    public static void glBindBufferBase(int target, int index, int buffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glBindBufferBase(target, index, buffer));
    }

    /** 资源分配：阻塞通道取回真实 VAO id。 */
    public static int glGenVertexArrays() {
        return BridgeSupport.blockingGetResource(org.lwjgl.opengl.GL30::glGenVertexArrays);
    }

    public static void glBindVertexArray(int array) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glBindVertexArray(array));
    }

    public static void glDeleteVertexArrays(int array) {
        if (BridgeSupport.dropAuxMutation("glDeleteVertexArrays")) {
            return;
        }
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glDeleteVertexArrays(array));
    }

    /** 带索引 getter（transform feedback 等）：阻塞通道取回。 */
    public static int glGetInteger(int pname, int index) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL30.glGetInteger(pname, index));
    }

    /**
     * 区间映射：纯写映射（WRITE 且无 READ）走 {@link BufferMapEmulator} 镜像仿真，
     * 零阻塞（unmap 时快照入队上传，见 {@link GL15#glUnmapBuffer(int)}）；
     * 其余形态（含 READ/重入/未跟踪 target）回退阻塞通道取回真实映射 buffer。
     */
    public static ByteBuffer glMapBufferRange(int target, long offset, long length, int access,
                                              ByteBuffer oldBuffer) {
        ByteBuffer mirror = BufferMapEmulator.tryEmulateMapRange(target, offset, length, access);
        if (mirror != null) {
            return mirror;
        }
        return BridgeSupport.blockingGet(() ->
                org.lwjgl.opengl.GL30.glMapBufferRange(target, offset, length, access, oldBuffer));
    }

    public static void glUniform1ui(int location, int v0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glUniform1ui(location, v0));
    }

    public static void glUniform2ui(int location, int v0, int v1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glUniform2ui(location, v0, v1));
    }

    public static void glUniform3ui(int location, int v0, int v1, int v2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glUniform3ui(location, v0, v1, v2));
    }

    /** 无符号整型顶点属性（VBO 偏移形态）。 */
    public static void glVertexAttribIPointer(int index, int size, int type, int stride, long offset) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL30.glVertexAttribIPointer(index, size, type, stride, offset));
    }
}
