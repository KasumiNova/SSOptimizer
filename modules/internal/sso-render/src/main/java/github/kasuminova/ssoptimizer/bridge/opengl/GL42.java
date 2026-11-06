package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

public final class GL42 extends GL42Gen {
    private GL42() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glBindImageTexture(int unit, int texture, int level, boolean layered,
                                          int layer, int access, int format) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glBindImageTexture(
                unit, texture, level, layered, layer, access, format));
    }

    public static void glMemoryBarrier(int barriers) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glMemoryBarrier(barriers));
    }

    public static void glTexStorage1D(int target, int levels, int internalFormat, int width) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glTexStorage1D(target, levels, internalFormat, width));
    }

    public static void glTexStorage2D(int target, int levels, int internalFormat, int width, int height) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glTexStorage2D(target, levels, internalFormat, width, height));
    }

    public static void glTexStorage3D(int target, int levels, int internalFormat,
                                      int width, int height, int depth) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glTexStorage3D(
                target, levels, internalFormat, width, height, depth));
    }


    public static void glDrawArraysInstancedBaseInstance(int mode, int first, int count,
                                                         int primcount, int baseInstance) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glDrawArraysInstancedBaseInstance(
                mode, first, count, primcount, baseInstance));
    }

    public static void glDrawElementsInstancedBaseInstance(int mode, java.nio.ByteBuffer indices,
                                                           int primcount, int baseInstance) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance(mode, snapshot, primcount, baseInstance));
    }

    public static void glDrawElementsInstancedBaseInstance(int mode, java.nio.IntBuffer indices,
                                                           int primcount, int baseInstance) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance(mode, snapshot.asIntBuffer(),
                        primcount, baseInstance));
    }

    public static void glDrawElementsInstancedBaseInstance(int mode, java.nio.ShortBuffer indices,
                                                           int primcount, int baseInstance) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance(mode, snapshot.asShortBuffer(),
                        primcount, baseInstance));
    }

    public static void glDrawElementsInstancedBaseInstance(int mode, int count, int type, long indicesOffset,
                                                           int primcount, int baseInstance) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance(
                mode, count, type, indicesOffset, primcount, baseInstance));
    }

    public static void glDrawElementsInstancedBaseVertexBaseInstance(int mode, java.nio.ByteBuffer indices,
                                                                     int primcount, int baseVertex, int baseInstance) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseVertexBaseInstance(mode, snapshot,
                        primcount, baseVertex, baseInstance));
    }

    public static void glDrawElementsInstancedBaseVertexBaseInstance(int mode, java.nio.IntBuffer indices,
                                                                     int primcount, int baseVertex, int baseInstance) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseVertexBaseInstance(mode, snapshot.asIntBuffer(),
                        primcount, baseVertex, baseInstance));
    }

    public static void glDrawElementsInstancedBaseVertexBaseInstance(int mode, java.nio.ShortBuffer indices,
                                                                     int primcount, int baseVertex, int baseInstance) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseVertexBaseInstance(mode, snapshot.asShortBuffer(),
                        primcount, baseVertex, baseInstance));
    }

    public static void glDrawElementsInstancedBaseVertexBaseInstance(int mode, int count, int type, long indicesOffset,
                                                                     int primcount, int baseVertex, int baseInstance) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseVertexBaseInstance(
                mode, count, type, indicesOffset, primcount, baseVertex, baseInstance));
    }
}
