package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.IntBuffer;

public final class GL40 extends GL40Gen {
    private GL40() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glBlendEquationi(int buf, int mode) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL40.glBlendEquationi(buf, mode));
    }

    public static void glBlendFunci(int buf, int src, int dst) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL40.glBlendFunci(buf, src, dst));
    }

    public static void glBlendFuncSeparatei(int buf, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL40.glBlendFuncSeparatei(buf, srcRGB, dstRGB, srcAlpha, dstAlpha));
    }

    public static void glPatchParameteri(int pname, int value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL40.glPatchParameteri(pname, value));
    }

    public static void glUniformSubroutinesu(int shadertype, IntBuffer indices) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL40.glUniformSubroutinesu(shadertype, snapshot.asIntBuffer()));
    }

    public static void glPatchParameter(int pname, java.nio.FloatBuffer values) {
        BridgeSupport.enqueueSnapshot(values, snapshot ->
                org.lwjgl.opengl.GL40.glPatchParameter(pname, snapshot.asFloatBuffer()));
    }

    public static int glGetSubroutineIndex(int program, int shadertype, CharSequence name) {
        String nameStr = name.toString();
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL40.glGetSubroutineIndex(program, shadertype, nameStr));
    }

    public static int glGetSubroutineUniformLocation(int program, int shadertype, CharSequence name) {
        String nameStr = name.toString();
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL40.glGetSubroutineUniformLocation(program, shadertype, nameStr));
    }


    public static void glDrawArraysIndirect(int mode, java.nio.ByteBuffer indirect) {
        BridgeSupport.enqueueSnapshot(indirect, snapshot ->
                org.lwjgl.opengl.GL40.glDrawArraysIndirect(mode, snapshot));
    }

    public static void glDrawArraysIndirect(int mode, IntBuffer indirect) {
        BridgeSupport.enqueueSnapshot(indirect, snapshot ->
                org.lwjgl.opengl.GL40.glDrawArraysIndirect(mode, snapshot.asIntBuffer()));
    }

    public static void glDrawArraysIndirect(int mode, long indirectOffset) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL40.glDrawArraysIndirect(mode, indirectOffset));
    }

    public static void glDrawElementsIndirect(int mode, int type, java.nio.ByteBuffer indirect) {
        BridgeSupport.enqueueSnapshot(indirect, snapshot ->
                org.lwjgl.opengl.GL40.glDrawElementsIndirect(mode, type, snapshot));
    }

    public static void glDrawElementsIndirect(int mode, int type, IntBuffer indirect) {
        BridgeSupport.enqueueSnapshot(indirect, snapshot ->
                org.lwjgl.opengl.GL40.glDrawElementsIndirect(mode, type, snapshot.asIntBuffer()));
    }

    public static void glDrawElementsIndirect(int mode, int type, long indirectOffset) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL40.glDrawElementsIndirect(mode, type, indirectOffset));
    }
}
