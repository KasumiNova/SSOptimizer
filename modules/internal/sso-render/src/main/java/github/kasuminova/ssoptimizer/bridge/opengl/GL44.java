package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public final class GL44 extends GL44Gen {
    private GL44() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glBufferStorage(int target, long size, int flags) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL44.glBufferStorage(target, size, flags));
    }

    public static void glBufferStorage(int target, ByteBuffer data, int flags) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL44.glBufferStorage(target, snapshot, flags));
    }

    public static void glBufferStorage(int target, FloatBuffer data, int flags) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL44.glBufferStorage(target, snapshot.asFloatBuffer(), flags));
    }

    public static void glBufferStorage(int target, java.nio.DoubleBuffer data, int flags) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL44.glBufferStorage(target, snapshot.asDoubleBuffer(), flags));
    }

    public static void glBufferStorage(int target, java.nio.IntBuffer data, int flags) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL44.glBufferStorage(target, snapshot.asIntBuffer(), flags));
    }

    public static void glBufferStorage(int target, java.nio.ShortBuffer data, int flags) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL44.glBufferStorage(target, snapshot.asShortBuffer(), flags));
    }

    public static void glBindTextures(int first, int count, java.nio.IntBuffer textures) {
        BridgeSupport.enqueueSnapshot(textures, snapshot ->
                org.lwjgl.opengl.GL44.glBindTextures(first, count, snapshot.asIntBuffer()));
    }
}
