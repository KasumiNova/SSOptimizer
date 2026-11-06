package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

public final class GL12 extends GL12Gen {
    private GL12() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glTexImage3D(int target, int level, int internalFormat,
                                    int width, int height, int depth,
                                    int border, int format, int type, ByteBuffer pixels) {
        if (pixels == null) {
            BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL12.glTexImage3D(target, level,
                    internalFormat, width, height, depth, border, format, type, (ByteBuffer) null));
        } else {
            BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                    org.lwjgl.opengl.GL12.glTexImage3D(target, level, internalFormat,
                            width, height, depth, border, format, type, snapshot));
        }
    }

    public static void glTexSubImage3D(int target, int level,
                                       int xOffset, int yOffset, int zOffset,
                                       int width, int height, int depth,
                                       int format, int type, FloatBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexSubImage3D(target, level,
                        xOffset, yOffset, zOffset, width, height, depth,
                        format, type, snapshot.asFloatBuffer()));
    }


    public static void glTexImage3D(int target, int level, int internalFormat,
                                    int width, int height, int depth,
                                    int border, int format, int type, long pixels) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL12.glTexImage3D(target, level,
                internalFormat, width, height, depth, border, format, type, pixels));
    }

    public static void glTexImage3D(int target, int level, int internalFormat,
                                    int width, int height, int depth,
                                    int border, int format, int type, DoubleBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexImage3D(target, level, internalFormat,
                        width, height, depth, border, format, type, snapshot.asDoubleBuffer()));
    }

    public static void glTexImage3D(int target, int level, int internalFormat,
                                    int width, int height, int depth,
                                    int border, int format, int type, FloatBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexImage3D(target, level, internalFormat,
                        width, height, depth, border, format, type, snapshot.asFloatBuffer()));
    }

    public static void glTexImage3D(int target, int level, int internalFormat,
                                    int width, int height, int depth,
                                    int border, int format, int type, IntBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexImage3D(target, level, internalFormat,
                        width, height, depth, border, format, type, snapshot.asIntBuffer()));
    }

    public static void glTexImage3D(int target, int level, int internalFormat,
                                    int width, int height, int depth,
                                    int border, int format, int type, ShortBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexImage3D(target, level, internalFormat,
                        width, height, depth, border, format, type, snapshot.asShortBuffer()));
    }

    public static void glTexSubImage3D(int target, int level,
                                       int xOffset, int yOffset, int zOffset,
                                       int width, int height, int depth,
                                       int format, int type, long pixels) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL12.glTexSubImage3D(target, level,
                xOffset, yOffset, zOffset, width, height, depth, format, type, pixels));
    }

    public static void glTexSubImage3D(int target, int level,
                                       int xOffset, int yOffset, int zOffset,
                                       int width, int height, int depth,
                                       int format, int type, ByteBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexSubImage3D(target, level,
                        xOffset, yOffset, zOffset, width, height, depth,
                        format, type, snapshot));
    }

    public static void glTexSubImage3D(int target, int level,
                                       int xOffset, int yOffset, int zOffset,
                                       int width, int height, int depth,
                                       int format, int type, DoubleBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexSubImage3D(target, level,
                        xOffset, yOffset, zOffset, width, height, depth,
                        format, type, snapshot.asDoubleBuffer()));
    }

    public static void glTexSubImage3D(int target, int level,
                                       int xOffset, int yOffset, int zOffset,
                                       int width, int height, int depth,
                                       int format, int type, IntBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexSubImage3D(target, level,
                        xOffset, yOffset, zOffset, width, height, depth,
                        format, type, snapshot.asIntBuffer()));
    }

    public static void glTexSubImage3D(int target, int level,
                                       int xOffset, int yOffset, int zOffset,
                                       int width, int height, int depth,
                                       int format, int type, ShortBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexSubImage3D(target, level,
                        xOffset, yOffset, zOffset, width, height, depth,
                        format, type, snapshot.asShortBuffer()));
    }


    public static void glDrawRangeElements(int mode, int start, int end, ByteBuffer indices) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setDrawRangeElementsSnapshot(mode, start, end, BridgeSupport.pool().snapshot(indices),
                DrawCommand.VIEW_BYTE, BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }

    public static void glDrawRangeElements(int mode, int start, int end, IntBuffer indices) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setDrawRangeElementsSnapshot(mode, start, end, BridgeSupport.pool().snapshot(indices),
                DrawCommand.VIEW_INT, BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }

    public static void glDrawRangeElements(int mode, int start, int end, ShortBuffer indices) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setDrawRangeElementsSnapshot(mode, start, end, BridgeSupport.pool().snapshot(indices),
                DrawCommand.VIEW_SHORT, BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }

    public static void glDrawRangeElements(int mode, int start, int end, int count, int type, long offset) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setDrawRangeElementsOffset(mode, start, end, count, type, offset,
                BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }
}
