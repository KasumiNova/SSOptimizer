package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

public final class GL13 extends GL13Gen {
    private GL13() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glActiveTexture(int texture) {
        BridgeSupport.simulatedState().onActiveTexture(texture);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glActiveTexture(texture));
    }

    public static void glCompressedTexImage2D(int target, int level, int internalformat, int width, int height,
                                              int border, java.nio.ByteBuffer data) {
        BridgeSupport.simulatedState().onTexImage2D(target, level, internalformat, width, height);
        BridgeSupport.enqueueSnapshot(data, snapshot -> org.lwjgl.opengl.GL13.glCompressedTexImage2D(
                target, level, internalformat, width, height, border, snapshot));
    }


    public static void glClientActiveTexture(int texture) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glClientActiveTexture(texture));
    }

    public static void glCompressedTexImage1D(int target, int level, int internalformat, int width,
                                              int border, java.nio.ByteBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot -> org.lwjgl.opengl.GL13.glCompressedTexImage1D(
                target, level, internalformat, width, border, snapshot));
    }

    public static void glCompressedTexImage1D(int target, int level, int internalformat, int width,
                                              int border, int imageSize, long dataOffset) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glCompressedTexImage1D(
                target, level, internalformat, width, border, imageSize, dataOffset));
    }

    public static void glCompressedTexImage2D(int target, int level, int internalformat, int width, int height,
                                              int border, int imageSize, long dataOffset) {
        BridgeSupport.simulatedState().onTexImage2D(target, level, internalformat, width, height);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glCompressedTexImage2D(
                target, level, internalformat, width, height, border, imageSize, dataOffset));
    }

    public static void glCompressedTexImage3D(int target, int level, int internalformat,
                                              int width, int height, int depth,
                                              int border, java.nio.ByteBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot -> org.lwjgl.opengl.GL13.glCompressedTexImage3D(
                target, level, internalformat, width, height, depth, border, snapshot));
    }

    public static void glCompressedTexImage3D(int target, int level, int internalformat,
                                              int width, int height, int depth,
                                              int border, int imageSize, long dataOffset) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glCompressedTexImage3D(
                target, level, internalformat, width, height, depth, border, imageSize, dataOffset));
    }

    public static void glCompressedTexSubImage1D(int target, int level, int xOffset, int width,
                                                 int format, java.nio.ByteBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot -> org.lwjgl.opengl.GL13.glCompressedTexSubImage1D(
                target, level, xOffset, width, format, snapshot));
    }

    public static void glCompressedTexSubImage1D(int target, int level, int xOffset, int width,
                                                 int format, int imageSize, long dataOffset) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glCompressedTexSubImage1D(
                target, level, xOffset, width, format, imageSize, dataOffset));
    }

    public static void glCompressedTexSubImage2D(int target, int level, int xOffset, int yOffset,
                                                 int width, int height, int format, java.nio.ByteBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot -> org.lwjgl.opengl.GL13.glCompressedTexSubImage2D(
                target, level, xOffset, yOffset, width, height, format, snapshot));
    }

    public static void glCompressedTexSubImage2D(int target, int level, int xOffset, int yOffset,
                                                 int width, int height, int format, int imageSize, long dataOffset) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glCompressedTexSubImage2D(
                target, level, xOffset, yOffset, width, height, format, imageSize, dataOffset));
    }

    public static void glCompressedTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset,
                                                 int width, int height, int depth, int format,
                                                 java.nio.ByteBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot -> org.lwjgl.opengl.GL13.glCompressedTexSubImage3D(
                target, level, xOffset, yOffset, zOffset, width, height, depth, format, snapshot));
    }

    public static void glCompressedTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset,
                                                 int width, int height, int depth, int format,
                                                 int imageSize, long dataOffset) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glCompressedTexSubImage3D(
                target, level, xOffset, yOffset, zOffset, width, height, depth, format, imageSize, dataOffset));
    }

    public static void glGetCompressedTexImage(int target, int level, java.nio.ByteBuffer data) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL13.glGetCompressedTexImage(target, level, data));
    }

    public static void glGetCompressedTexImage(int target, int level, long dataOffset) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL13.glGetCompressedTexImage(target, level, dataOffset));
    }
}
