package github.kasuminova.ssoptimizer.bridge.opengl;

public class GL43Gen {
    protected GL43Gen() {
    }

    public static void glBindVertexBuffer(int p0, int p1, long p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glBindVertexBuffer(p0, p1, p2, p3));
    }

    public static void glClearBufferData(int p0, int p1, int p2, int p3, java.nio.ByteBuffer p4) {
        BridgeSupport.enqueueSnapshot(p4, snapshot -> org.lwjgl.opengl.GL43.glClearBufferData(p0, p1, p2, p3, snapshot));
    }

    public static void glClearBufferSubData(int p0, int p1, long p2, long p3, int p4, int p5, java.nio.ByteBuffer p6) {
        BridgeSupport.enqueueSnapshot(p6, snapshot -> org.lwjgl.opengl.GL43.glClearBufferSubData(p0, p1, p2, p3, p4, p5, snapshot));
    }

    public static void glCopyImageSubData(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, int p10, int p11, int p12, int p13, int p14) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glCopyImageSubData(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14));
    }

    // 回调由渲染线程触发。
    public static void glDebugMessageCallback(org.lwjgl.opengl.KHRDebugCallback p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glDebugMessageCallback(p0));
    }

    public static void glDebugMessageControl(int p0, int p1, int p2, java.nio.IntBuffer p3, boolean p4) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.GL43.glDebugMessageControl(p0, p1, p2, snapshot.asIntBuffer(), p4));
    }

    public static void glDebugMessageInsert(int p0, int p1, int p2, int p3, java.lang.CharSequence p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glDebugMessageInsert(p0, p1, p2, p3, p4));
    }

    public static void glDebugMessageInsert(int p0, int p1, int p2, int p3, java.nio.ByteBuffer p4) {
        BridgeSupport.enqueueSnapshot(p4, snapshot -> org.lwjgl.opengl.GL43.glDebugMessageInsert(p0, p1, p2, p3, snapshot));
    }

    public static void glDispatchCompute(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glDispatchCompute(p0, p1, p2));
    }

    public static void glDispatchComputeIndirect(long p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glDispatchComputeIndirect(p0));
    }

    public static void glFramebufferParameteri(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glFramebufferParameteri(p0, p1, p2));
    }

    public static int glGetDebugMessageLog(int p0, java.nio.IntBuffer p1, java.nio.IntBuffer p2, java.nio.IntBuffer p3, java.nio.IntBuffer p4, java.nio.IntBuffer p5, java.nio.ByteBuffer p6) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL43.glGetDebugMessageLog(p0, p1, p2, p3, p4, p5, p6));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetFramebufferParameter(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.GL43.glGetFramebufferParameter(p0, p1, buf)));
    }

    public static int glGetFramebufferParameteri(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL43.glGetFramebufferParameteri(p0, p1));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetInternalformat(int p0, int p1, int p2, java.nio.LongBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillLongs(p3, 16, buf -> org.lwjgl.opengl.GL43.glGetInternalformat(p0, p1, p2, buf)));
    }

    public static long glGetInternalformati64(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL43.glGetInternalformati64(p0, p1, p2));
    }

    public static java.lang.String glGetObjectLabel(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL43.glGetObjectLabel(p0, p1, p2));
    }

    public static void glGetObjectLabel(int p0, int p1, java.nio.IntBuffer p2, java.nio.ByteBuffer p3) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL43.glGetObjectLabel(p0, p1, p2, p3));
    }

    public static java.lang.String glGetObjectPtrLabel(org.lwjgl.PointerWrapper p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL43.glGetObjectPtrLabel(p0, p1));
    }

    public static void glGetObjectPtrLabel(org.lwjgl.PointerWrapper p0, java.nio.IntBuffer p1, java.nio.ByteBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL43.glGetObjectPtrLabel(p0, p1, p2));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetProgramInterface(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p3, 16, buf -> org.lwjgl.opengl.GL43.glGetProgramInterface(p0, p1, p2, buf)));
    }

    public static int glGetProgramInterfacei(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL43.glGetProgramInterfacei(p0, p1, p2));
    }

    public static void glGetProgramResource(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.IntBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL43.glGetProgramResource(p0, p1, p2, p3, p4, p5));
    }

    public static int glGetProgramResourceIndex(int p0, int p1, java.lang.CharSequence p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL43.glGetProgramResourceIndex(p0, p1, p2));
    }

    public static int glGetProgramResourceIndex(int p0, int p1, java.nio.ByteBuffer p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL43.glGetProgramResourceIndex(p0, p1, p2));
    }

    public static int glGetProgramResourceLocation(int p0, int p1, java.lang.CharSequence p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL43.glGetProgramResourceLocation(p0, p1, p2));
    }

    public static int glGetProgramResourceLocation(int p0, int p1, java.nio.ByteBuffer p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL43.glGetProgramResourceLocation(p0, p1, p2));
    }

    public static int glGetProgramResourceLocationIndex(int p0, int p1, java.lang.CharSequence p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL43.glGetProgramResourceLocationIndex(p0, p1, p2));
    }

    public static int glGetProgramResourceLocationIndex(int p0, int p1, java.nio.ByteBuffer p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL43.glGetProgramResourceLocationIndex(p0, p1, p2));
    }

    public static java.lang.String glGetProgramResourceName(int p0, int p1, int p2, int p3) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL43.glGetProgramResourceName(p0, p1, p2, p3));
    }

    public static void glGetProgramResourceName(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.ByteBuffer p4) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL43.glGetProgramResourceName(p0, p1, p2, p3, p4));
    }

    public static void glInvalidateBufferData(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glInvalidateBufferData(p0));
    }

    public static void glInvalidateBufferSubData(int p0, long p1, long p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glInvalidateBufferSubData(p0, p1, p2));
    }

    public static void glInvalidateFramebuffer(int p0, java.nio.IntBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL43.glInvalidateFramebuffer(p0, snapshot.asIntBuffer()));
    }

    public static void glInvalidateSubFramebuffer(int p0, java.nio.IntBuffer p1, int p2, int p3, int p4, int p5) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL43.glInvalidateSubFramebuffer(p0, snapshot.asIntBuffer(), p2, p3, p4, p5));
    }

    public static void glInvalidateTexImage(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glInvalidateTexImage(p0, p1));
    }

    public static void glInvalidateTexSubImage(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glInvalidateTexSubImage(p0, p1, p2, p3, p4, p5, p6, p7));
    }

    public static void glMultiDrawArraysIndirect(int p0, java.nio.ByteBuffer p1, int p2, int p3) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL43.glMultiDrawArraysIndirect(p0, snapshot, p2, p3));
    }

    public static void glMultiDrawArraysIndirect(int p0, java.nio.IntBuffer p1, int p2, int p3) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL43.glMultiDrawArraysIndirect(p0, snapshot.asIntBuffer(), p2, p3));
    }

    public static void glMultiDrawArraysIndirect(int p0, long p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glMultiDrawArraysIndirect(p0, p1, p2, p3));
    }

    public static void glMultiDrawElementsIndirect(int p0, int p1, java.nio.ByteBuffer p2, int p3, int p4) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect(p0, p1, snapshot, p3, p4));
    }

    public static void glMultiDrawElementsIndirect(int p0, int p1, java.nio.IntBuffer p2, int p3, int p4) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect(p0, p1, snapshot.asIntBuffer(), p3, p4));
    }

    public static void glMultiDrawElementsIndirect(int p0, int p1, long p2, int p3, int p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect(p0, p1, p2, p3, p4));
    }

    public static void glObjectLabel(int p0, int p1, java.lang.CharSequence p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glObjectLabel(p0, p1, p2));
    }

    public static void glObjectLabel(int p0, int p1, java.nio.ByteBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL43.glObjectLabel(p0, p1, snapshot));
    }

    public static void glObjectPtrLabel(org.lwjgl.PointerWrapper p0, java.lang.CharSequence p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glObjectPtrLabel(p0, p1));
    }

    public static void glObjectPtrLabel(org.lwjgl.PointerWrapper p0, java.nio.ByteBuffer p1) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL43.glObjectPtrLabel(p0, p1));
    }

    public static void glPopDebugGroup() {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glPopDebugGroup());
    }

    public static void glPushDebugGroup(int p0, int p1, java.lang.CharSequence p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glPushDebugGroup(p0, p1, p2));
    }

    public static void glPushDebugGroup(int p0, int p1, java.nio.ByteBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL43.glPushDebugGroup(p0, p1, snapshot));
    }

    public static void glShaderStorageBlockBinding(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glShaderStorageBlockBinding(p0, p1, p2));
    }

    public static void glTexBufferRange(int p0, int p1, int p2, long p3, long p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glTexBufferRange(p0, p1, p2, p3, p4));
    }

    public static void glTexStorage2DMultisample(int p0, int p1, int p2, int p3, int p4, boolean p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glTexStorage2DMultisample(p0, p1, p2, p3, p4, p5));
    }

    public static void glTexStorage3DMultisample(int p0, int p1, int p2, int p3, int p4, int p5, boolean p6) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glTexStorage3DMultisample(p0, p1, p2, p3, p4, p5, p6));
    }

    public static void glTextureView(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glTextureView(p0, p1, p2, p3, p4, p5, p6, p7));
    }

    public static void glVertexAttribBinding(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glVertexAttribBinding(p0, p1));
    }

    public static void glVertexAttribFormat(int p0, int p1, int p2, boolean p3, int p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glVertexAttribFormat(p0, p1, p2, p3, p4));
    }

    public static void glVertexAttribIFormat(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glVertexAttribIFormat(p0, p1, p2, p3));
    }

    public static void glVertexAttribLFormat(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glVertexAttribLFormat(p0, p1, p2, p3));
    }

    public static void glVertexBindingDivisor(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glVertexBindingDivisor(p0, p1));
    }
}
