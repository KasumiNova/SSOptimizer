package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public final class GL30 extends GL30Gen {
    private GL30() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glBindFramebuffer(int target, int framebuffer) {
        if (target == org.lwjgl.opengl.GL30.GL_FRAMEBUFFER) {
            BridgeSupport.setFramebufferBinding(framebuffer);
        }
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "BIND_FBO", target, framebuffer, 0, null);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glBindFramebuffer(target, framebuffer));
    }

    public static int glGenFramebuffers() {
        return BridgeSupport.blockingGetResource(org.lwjgl.opengl.GL30::glGenFramebuffers);
    }

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

    public static int glGenRenderbuffers() {
        return BridgeSupport.blockingGetResource(org.lwjgl.opengl.GL30::glGenRenderbuffers);
    }

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

    public static int glGenVertexArrays() {
        return BridgeSupport.blockingGetResource(org.lwjgl.opengl.GL30::glGenVertexArrays);
    }

    public static void glBindVertexArray(int array) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glBindVertexArray(array));
    }

    public static void glDeleteVertexArrays(int array) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glDeleteVertexArrays(array));
    }

    public static int glGetInteger(int pname, int index) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL30.glGetInteger(pname, index));
    }

    /** 纯写映射使用录制侧仿真；读取、重入和未跟踪 target 阻塞映射真实 buffer。 */
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
        final ByteBuffer mapped = BridgeSupport.blockingGet(() ->
                org.lwjgl.opengl.GL30.glMapBufferRange(target, offset, length, access, oldBuffer));
        // 流序写入凭生命周期令牌复查映射存活，避免访问已释放的映射内存。
        if (mapped != null) {
            RealMappingRegistry.track(BufferMapEmulator.boundBufferForCurrentThread(target), mapped);
        }
        return mapped;
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

    public static void glVertexAttribIPointer(int index, int size, int type, int stride, long offset) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL30.glVertexAttribIPointer(index, size, type, stride, offset));
    }


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

    public static void glClearBuffer(int buffer, int drawbuffer, java.nio.FloatBuffer value) {
        BridgeSupport.enqueueSnapshot(value, snapshot ->
                org.lwjgl.opengl.GL30.glClearBuffer(buffer, drawbuffer, snapshot.asFloatBuffer()));
    }

    public static void glClearBuffer(int buffer, int drawbuffer, IntBuffer value) {
        BridgeSupport.enqueueSnapshot(value, snapshot ->
                org.lwjgl.opengl.GL30.glClearBuffer(buffer, drawbuffer, snapshot.asIntBuffer()));
    }

    public static void glClearBufferu(int buffer, int drawbuffer, IntBuffer value) {
        BridgeSupport.enqueueSnapshot(value, snapshot ->
                org.lwjgl.opengl.GL30.glClearBufferu(buffer, drawbuffer, snapshot.asIntBuffer()));
    }

    public static void glClearBufferfi(int buffer, int drawbuffer, float depth, int stencil) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glClearBufferfi(buffer, drawbuffer, depth, stencil));
    }

    public static void glGenVertexArrays(IntBuffer arrays) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.GL30.glGenVertexArrays(arrays));
    }

    public static void glDeleteVertexArrays(IntBuffer arrays) {
        BridgeSupport.enqueueSnapshot(arrays, snapshot ->
                org.lwjgl.opengl.GL30.glDeleteVertexArrays(snapshot.asIntBuffer()));
    }

    public static void glBindFragDataLocation(int program, int colorNumber, CharSequence name) {
        String nameStr = name.toString();
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glBindFragDataLocation(program, colorNumber, nameStr));
    }

    /** 仿真映射由 unmap 整体上传，因此 flush 无需入队；真实映射仍按提交序执行。 */
    public static void glFlushMappedBufferRange(int target, long offset, long length) {
        if (BufferMapEmulator.hasPendingMap(target)) {
            return;
        }
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL30.glFlushMappedBufferRange(target, offset, length));
    }
}
