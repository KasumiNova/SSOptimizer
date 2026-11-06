package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

public final class GL41 extends GL41Gen {
    private GL41() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glClearDepthf(float d) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL41.glClearDepthf(d));
    }

    public static void glDepthRangef(float n, float f) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL41.glDepthRangef(n, f));
    }

    public static void glProgramUniform1f(int program, int location, float v0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL41.glProgramUniform1f(program, location, v0));
    }

    public static void glProgramUniform1i(int program, int location, int v0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL41.glProgramUniform1i(program, location, v0));
    }

    public static void glProgramUniform2f(int program, int location, float v0, float v1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL41.glProgramUniform2f(program, location, v0, v1));
    }

    public static void glVertexAttribLPointer(int index, int size, int stride, long offset) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL41.glVertexAttribLPointer(index, size, stride, offset));
    }
}
