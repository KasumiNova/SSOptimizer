package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

public final class GL15 extends GL15Gen {
    private GL15() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glBindBuffer(int target, int buffer) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "BIND_BUF", target, buffer, 0, null);
        BufferMapEmulator.onBindBuffer(target, buffer);
        BridgeSupport.simulatedState().onBindBuffer(target, buffer);
        final String recordThread;
        if (target == org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER) {
            // 录制侧与执行侧同步跟踪 ARRAY_BUFFER 绑定，供延迟到 draw 的 pointer 快照恢复。
            BridgeSupport.pointerState().setArrayBufferBinding(buffer);
            recordThread = Thread.currentThread().getName();
        } else {
            recordThread = null;
        }
        BridgeSupport.enqueue(() -> {
            org.lwjgl.opengl.GL15.glBindBuffer(target, buffer);
            if (target == org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER) {
                // 执行侧同步记录绑定与录制线程，供 pointer 快照恢复和失败诊断。
                BridgeSupport.executedArrayBufferBinding(buffer, "GL15", recordThread);
            }
        });
    }

    public static int glGenBuffers() {
        return BridgeSupport.acquireBufferId();
    }

    public static void glGenBuffers(IntBuffer buffers) {
        BridgeSupport.blockingWaitResource(() -> {
            org.lwjgl.opengl.GL15.glGenBuffers(buffers);
            int[] written = new int[buffers.remaining()];
            buffers.slice().get(written);
            BridgeSupport.validateGeneratedBufferIds(written, org.lwjgl.opengl.GL11::glGetError);
        });
    }

    public static void glBufferData(int target, long size, int usage) {
        BufferMapEmulator.onBufferData(target, size);
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "BUFFER_DATA", target, size, usage, null);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL15.glBufferData(target, size, usage));
    }

    public static void glBufferData(int target, ByteBuffer data, int usage) {
        BufferMapEmulator.onBufferData(target, data.remaining());
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferData(target, snapshot, usage));
    }

    public static void glBufferData(int target, DoubleBuffer data, int usage) {
        BufferMapEmulator.onBufferData(target, (long) data.remaining() << 3);
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferData(target, snapshot.asDoubleBuffer(), usage));
    }

    public static void glBufferData(int target, FloatBuffer data, int usage) {
        BufferMapEmulator.onBufferData(target, (long) data.remaining() << 2);
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferData(target, snapshot.asFloatBuffer(), usage));
    }

    public static void glBufferData(int target, IntBuffer data, int usage) {
        BufferMapEmulator.onBufferData(target, (long) data.remaining() << 2);
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferData(target, snapshot.asIntBuffer(), usage));
    }

    public static void glBufferData(int target, ShortBuffer data, int usage) {
        BufferMapEmulator.onBufferData(target, (long) data.remaining() << 1);
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferData(target, snapshot.asShortBuffer(), usage));
    }

    public static void glBufferSubData(int target, long offset, ByteBuffer data) {
        if (github.kasuminova.ssoptimizer.common.render.queue.RtTrace.enabled()) {
            github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                    "BUFFER_SUB", target, offset, data.remaining(),
                    target == org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER
                            ? github.kasuminova.ssoptimizer.common.render.queue.RtTrace.floatStats(data)
                            : null);
        }
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferSubData(target, offset, snapshot));
    }

    public static void glBufferSubData(int target, long offset, DoubleBuffer data) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "BUFFER_SUB", target, offset, (long) data.remaining() << 3, null);
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferSubData(target, offset, snapshot.asDoubleBuffer()));
    }

    public static void glBufferSubData(int target, long offset, FloatBuffer data) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "BUFFER_SUB", target, offset, (long) data.remaining() << 2, null);
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferSubData(target, offset, snapshot.asFloatBuffer()));
    }

    public static void glBufferSubData(int target, long offset, IntBuffer data) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "BUFFER_SUB", target, offset, (long) data.remaining() << 2, null);
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferSubData(target, offset, snapshot.asIntBuffer()));
    }

    public static void glBufferSubData(int target, long offset, ShortBuffer data) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "BUFFER_SUB", target, offset, (long) data.remaining() << 1, null);
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferSubData(target, offset, snapshot.asShortBuffer()));
    }

    public static void glDeleteBuffers(int buffer) {
        BufferMapEmulator.onDeleteBuffer(buffer);
        if (BridgeSupport.pointerState().arrayBufferBinding() == buffer) {
            // 删除当前绑定的 buffer 时，录制侧同步将 ARRAY_BUFFER 绑定重置为 0。
            BridgeSupport.pointerState().setArrayBufferBinding(0);
        }
        final String recordThread = Thread.currentThread().getName();
        BridgeSupport.enqueue(() -> {
            org.lwjgl.opengl.GL15.glDeleteBuffers(buffer);
            if (BridgeSupport.executedArrayBufferBinding() == buffer) {
                BridgeSupport.executedArrayBufferBinding(0, "GL15.glDeleteBuffers", recordThread);
            }
        });
    }

    public static void glDeleteBuffers(IntBuffer buffers) {
        while (buffers.hasRemaining()) {
            final int id = buffers.get();
            BufferMapEmulator.onDeleteBuffer(id);
            if (BridgeSupport.pointerState().arrayBufferBinding() == id) {
                BridgeSupport.pointerState().setArrayBufferBinding(0);
            }
        }
        buffers.rewind();
        final String recordThread = Thread.currentThread().getName();
        BridgeSupport.enqueueSnapshot(buffers, snapshot -> {
            final IntBuffer view = snapshot.asIntBuffer();
            final int count = view.remaining();
            org.lwjgl.opengl.GL15.glDeleteBuffers(view);
            for (int i = 0; i < count; i++) {
                if (BridgeSupport.executedArrayBufferBinding() == view.get(i)) {
                    BridgeSupport.executedArrayBufferBinding(0, "GL15.glDeleteBuffers", recordThread);
                }
            }
        });
    }

    /** 仿真映射在此快照上传；真实映射先失效令牌再阻塞解除，防止在途写入访问已释放内存。 */
    public static boolean glUnmapBuffer(int target) {
        BufferMapEmulator.PendingUpload upload = BufferMapEmulator.pollEmulatedUnmap(target);
        if (upload != null) {
            BufferMapEmulator.enqueueUpload(upload);
            return true;
        }
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace("UNMAP_REAL", target, 0, 0, null);
        RealMappingRegistry.invalidateBuffer(BufferMapEmulator.boundBufferForCurrentThread(target));
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL15.glUnmapBuffer(target));
    }


    public static void glGetBufferSubData(int target, long offset, ByteBuffer data) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL15.glGetBufferSubData(target, offset, data));
    }

    public static void glGetBufferSubData(int target, long offset, java.nio.DoubleBuffer data) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL15.glGetBufferSubData(target, offset, data));
    }

    public static void glGetBufferSubData(int target, long offset, FloatBuffer data) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL15.glGetBufferSubData(target, offset, data));
    }

    public static void glGetBufferSubData(int target, long offset, IntBuffer data) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL15.glGetBufferSubData(target, offset, data));
    }

    public static void glGetBufferSubData(int target, long offset, java.nio.ShortBuffer data) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL15.glGetBufferSubData(target, offset, data));
    }
}
