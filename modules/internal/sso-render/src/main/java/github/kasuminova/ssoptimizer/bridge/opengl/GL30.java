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
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "BIND_FBO", target, framebuffer, 0, null);
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
        if (github.kasuminova.ssoptimizer.common.render.queue.RtTrace.enabled()) {
            github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                    "BLIT", srcX1, srcY1, dstX1, "dstY1=" + dstY1 + " mask=" + mask);
        }
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
        if (github.kasuminova.ssoptimizer.common.render.queue.RtTrace.enabled()) {
            github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                    mirror != null ? "MAP_EMU" : "MAP_REAL", target, offset, length,
                    "access=0x" + Integer.toHexString(access));
        }
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

    // ------------------------------------------------------------------
    // 盘点补面：UBO range 绑定 / FBO 其余附着点 / 多重采样 renderbuffer /
    // glClearBuffer 族（BoxUtil 1.0.6 引用；语义同既有方法）
    // ------------------------------------------------------------------

    public static void glBindBufferRange(int target, int index, int buffer, long offset, long size) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL30.glBindBufferRange(target, index, buffer, offset, size));
    }

    public static void glFramebufferTexture1D(int target, int attachment, int textarget, int texture, int level) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL30.glFramebufferTexture1D(target, attachment, textarget, texture, level));
    }

    public static void glFramebufferTexture3D(int target, int attachment, int textarget, int texture,
                                              int level, int layer) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL30.glFramebufferTexture3D(target, attachment, textarget, texture, level, layer));
    }

    public static void glFramebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL30.glFramebufferTextureLayer(target, attachment, texture, level, layer));
    }

    public static void glRenderbufferStorageMultisample(int target, int samples, int internalformat,
                                                        int width, int height) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL30.glRenderbufferStorageMultisample(target, samples, internalformat, width, height));
    }

    /** 清除值录制时刻快照入队。 */
    public static void glClearBuffer(int buffer, int drawbuffer, java.nio.FloatBuffer value) {
        BridgeSupport.enqueueSnapshot(value, snapshot ->
                org.lwjgl.opengl.GL30.glClearBuffer(buffer, drawbuffer, snapshot.asFloatBuffer()));
    }

    /** 清除值录制时刻快照入队。 */
    public static void glClearBuffer(int buffer, int drawbuffer, IntBuffer value) {
        BridgeSupport.enqueueSnapshot(value, snapshot ->
                org.lwjgl.opengl.GL30.glClearBuffer(buffer, drawbuffer, snapshot.asIntBuffer()));
    }

    /** 清除值录制时刻快照入队。 */
    public static void glClearBufferu(int buffer, int drawbuffer, IntBuffer value) {
        BridgeSupport.enqueueSnapshot(value, snapshot ->
                org.lwjgl.opengl.GL30.glClearBufferu(buffer, drawbuffer, snapshot.asIntBuffer()));
    }

    public static void glClearBufferfi(int buffer, int drawbuffer, float depth, int stencil) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glClearBufferfi(buffer, drawbuffer, depth, stencil));
    }

    /** 渲染线程直接写入调用方 buffer；调用方阻塞期间 buffer 不被触碰。 */
    public static void glGenVertexArrays(IntBuffer arrays) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.GL30.glGenVertexArrays(arrays));
    }

    /** id 列表录制时刻快照入队。 */
    public static void glDeleteVertexArrays(IntBuffer arrays) {
        BridgeSupport.enqueueSnapshot(arrays, snapshot ->
                org.lwjgl.opengl.GL30.glDeleteVertexArrays(snapshot.asIntBuffer()));
    }

    /** 名称在录制时刻定稿（CharSequence 内容不可变假设同 glGetUniformBlockIndex）。 */
    public static void glBindFragDataLocation(int program, int colorNumber, CharSequence name) {
        String nameStr = name.toString();
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glBindFragDataLocation(program, colorNumber, nameStr));
    }

    /**
     * 显式 flush 映射区间：本线程在该 target 有在途<b>仿真</b>映射时为 no-op——
     * 仿真 unmap 会把整个映射区间快照上传（flush 区间的超集），语义正确（仅损失
     * flush 的增量优化意图）；无在途仿真映射说明走了真实映射，入队真实 flush。
     */
    public static void glFlushMappedBufferRange(int target, long offset, long length) {
        if (BufferMapEmulator.hasPendingMap(target)) {
            return;
        }
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glFlushMappedBufferRange(target, offset, length));
    }
}
