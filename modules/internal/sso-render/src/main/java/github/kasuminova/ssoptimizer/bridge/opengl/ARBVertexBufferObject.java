package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

public final class ARBVertexBufferObject extends ARBVertexBufferObjectGen {
    private ARBVertexBufferObject() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glBindBufferARB(int target, int buffer) {
        BridgeSupport.simulatedState().onBindBuffer(target, buffer);
        final String recordThread;
        if (target == org.lwjgl.opengl.ARBVertexBufferObject.GL_ARRAY_BUFFER_ARB) {
            BridgeSupport.pointerState().setArrayBufferBinding(buffer);
            recordThread = Thread.currentThread().getName();
        } else {
            recordThread = null;
        }
        BridgeSupport.enqueue(() -> {
            org.lwjgl.opengl.ARBVertexBufferObject.glBindBufferARB(target, buffer);
            if (target == org.lwjgl.opengl.ARBVertexBufferObject.GL_ARRAY_BUFFER_ARB) {
                BridgeSupport.executedArrayBufferBinding(buffer, "ARBVertexBufferObject", recordThread);
            }
        });
    }

    public static int glGenBuffersARB() {
        return BridgeSupport.acquireBufferId();
    }

    public static void glGenBuffersARB(IntBuffer buffers) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.ARBVertexBufferObject.glGenBuffersARB(buffers));
    }

    public static void glBufferDataARB(int target, long size, int usage) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexBufferObject.glBufferDataARB(target, size, usage));
    }

    public static void glBufferDataARB(int target, ByteBuffer data, int usage) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.ARBVertexBufferObject.glBufferDataARB(target, snapshot, usage));
    }

    public static void glBufferDataARB(int target, DoubleBuffer data, int usage) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.ARBVertexBufferObject.glBufferDataARB(target, snapshot.asDoubleBuffer(), usage));
    }

    public static void glBufferDataARB(int target, FloatBuffer data, int usage) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.ARBVertexBufferObject.glBufferDataARB(target, snapshot.asFloatBuffer(), usage));
    }

    public static void glBufferDataARB(int target, IntBuffer data, int usage) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.ARBVertexBufferObject.glBufferDataARB(target, snapshot.asIntBuffer(), usage));
    }

    public static void glBufferDataARB(int target, ShortBuffer data, int usage) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.ARBVertexBufferObject.glBufferDataARB(target, snapshot.asShortBuffer(), usage));
    }

    public static void glBufferSubDataARB(int target, long offset, ByteBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.ARBVertexBufferObject.glBufferSubDataARB(target, offset, snapshot));
    }

    public static void glBufferSubDataARB(int target, long offset, DoubleBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.ARBVertexBufferObject.glBufferSubDataARB(target, offset, snapshot.asDoubleBuffer()));
    }

    public static void glBufferSubDataARB(int target, long offset, FloatBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.ARBVertexBufferObject.glBufferSubDataARB(target, offset, snapshot.asFloatBuffer()));
    }

    public static void glBufferSubDataARB(int target, long offset, IntBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.ARBVertexBufferObject.glBufferSubDataARB(target, offset, snapshot.asIntBuffer()));
    }

    public static void glBufferSubDataARB(int target, long offset, ShortBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.ARBVertexBufferObject.glBufferSubDataARB(target, offset, snapshot.asShortBuffer()));
    }

    public static void glDeleteBuffersARB(int buffer) {
        if (BridgeSupport.pointerState().arrayBufferBinding() == buffer) {
            BridgeSupport.pointerState().setArrayBufferBinding(0);
        }
        final String recordThread = Thread.currentThread().getName();
        BridgeSupport.enqueue(() -> {
            org.lwjgl.opengl.ARBVertexBufferObject.glDeleteBuffersARB(buffer);
            if (BridgeSupport.executedArrayBufferBinding() == buffer) {
                BridgeSupport.executedArrayBufferBinding(
                        0, "ARBVertexBufferObject.glDeleteBuffersARB", recordThread);
            }
        });
    }

    public static void glDeleteBuffersARB(IntBuffer buffers) {
        while (buffers.hasRemaining()) {
            if (BridgeSupport.pointerState().arrayBufferBinding() == buffers.get()) {
                BridgeSupport.pointerState().setArrayBufferBinding(0);
            }
        }
        buffers.rewind();
        final String recordThread = Thread.currentThread().getName();
        BridgeSupport.enqueueSnapshot(buffers, snapshot -> {
            final IntBuffer view = snapshot.asIntBuffer();
            final int count = view.remaining();
            org.lwjgl.opengl.ARBVertexBufferObject.glDeleteBuffersARB(view);
            for (int i = 0; i < count; i++) {
                if (BridgeSupport.executedArrayBufferBinding() == view.get(i)) {
                    BridgeSupport.executedArrayBufferBinding(
                            0, "ARBVertexBufferObject.glDeleteBuffersARB", recordThread);
                }
            }
        });
    }
}
