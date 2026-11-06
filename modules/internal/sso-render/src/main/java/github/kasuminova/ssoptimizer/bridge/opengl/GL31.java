package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

public final class GL31 extends GL31Gen {
    private GL31() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glCopyBufferSubData(int readTarget, int writeTarget,
                                           long readOffset, long writeOffset, long size) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glCopyBufferSubData(
                readTarget, writeTarget, readOffset, writeOffset, size));
    }

    public static void glDrawArraysInstanced(int mode, int first, int count, int primcount) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "DRAW_INST", mode, count, primcount, null);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glDrawArraysInstanced(mode, first, count, primcount));
    }

    public static void glTexBuffer(int target, int internalFormat, int buffer) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "TEXBUF", target, internalFormat, buffer, null);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glTexBuffer(target, internalFormat, buffer));
    }

    public static void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glUniformBlockBinding(
                program, uniformBlockIndex, uniformBlockBinding));
    }

    public static int glGetUniformBlockIndex(int program, CharSequence uniformBlockName) {
        String name = uniformBlockName.toString();
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL31.glGetUniformBlockIndex(program, name));
    }


    public static void glDrawElementsInstanced(int mode, java.nio.ByteBuffer indices, int primcount) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL31.glDrawElementsInstanced(mode, snapshot, primcount));
    }

    public static void glDrawElementsInstanced(int mode, java.nio.IntBuffer indices, int primcount) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL31.glDrawElementsInstanced(mode, snapshot.asIntBuffer(), primcount));
    }

    public static void glDrawElementsInstanced(int mode, java.nio.ShortBuffer indices, int primcount) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL31.glDrawElementsInstanced(mode, snapshot.asShortBuffer(), primcount));
    }

    public static void glDrawElementsInstanced(int mode, int count, int type, long indicesOffset, int primcount) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL31.glDrawElementsInstanced(mode, count, type, indicesOffset, primcount));
    }


    public static int glGetUniformBlockIndex(int program, java.nio.ByteBuffer uniformBlockName) {
        String name = readNullTerminatedName(uniformBlockName);
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL31.glGetUniformBlockIndex(program, name));
    }

    public static int glGetActiveUniformBlocki(int program, int uniformBlockIndex, int pname) {
        return BridgeSupport.blockingGet(() ->
                org.lwjgl.opengl.GL31.glGetActiveUniformBlocki(program, uniformBlockIndex, pname));
    }

    public static int glGetActiveUniformsi(int program, int uniformIndex, int pname) {
        return BridgeSupport.blockingGet(() ->
                org.lwjgl.opengl.GL31.glGetActiveUniformsi(program, uniformIndex, pname));
    }

    public static String glGetActiveUniformBlockName(int program, int uniformBlockIndex, int maxLength) {
        return BridgeSupport.blockingGet(() ->
                org.lwjgl.opengl.GL31.glGetActiveUniformBlockName(program, uniformBlockIndex, maxLength));
    }

    public static String glGetActiveUniformName(int program, int uniformIndex, int maxLength) {
        return BridgeSupport.blockingGet(() ->
                org.lwjgl.opengl.GL31.glGetActiveUniformName(program, uniformIndex, maxLength));
    }

    /** LWJGL2 的 ByteBuffer 名称参数是 NUL 结尾 ASCII；录制时刻读出（不改 position）。 */
    private static String readNullTerminatedName(java.nio.ByteBuffer name) {
        java.nio.ByteBuffer view = name.duplicate();
        StringBuilder sb = new StringBuilder(view.remaining());
        while (view.hasRemaining()) {
            byte b = view.get();
            if (b == 0) {
                break;
            }
            sb.append((char) (b & 0xFF));
        }
        return sb.toString();
    }
}
