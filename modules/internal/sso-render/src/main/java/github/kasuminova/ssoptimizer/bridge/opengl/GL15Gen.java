package github.kasuminova.ssoptimizer.bridge.opengl;

public class GL15Gen {
    protected GL15Gen() {
    }

    public static void glBeginQuery(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL15.glBeginQuery(p0, p1));
    }

    // ARRAY_BUFFER 绑定在录制侧与执行侧同步簿记，不进入 BufferMapEmulator。
    public static void glBindBuffer(int p0, int p1) {
        BridgeSupport.simulatedState().onBindBuffer(p0, p1);
        final String recordThread;
        if (p0 == org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER) {
            BridgeSupport.pointerState().setArrayBufferBinding(p1);
            recordThread = Thread.currentThread().getName();
        } else {
            recordThread = null;
        }
        BridgeSupport.enqueue(() -> {
            org.lwjgl.opengl.GL15.glBindBuffer(p0, p1);
            if (p0 == org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER) {
                BridgeSupport.executedArrayBufferBinding(p1, "GL15", recordThread);
            }
        });
    }

    public static void glBufferData(int p0, java.nio.ByteBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL15.glBufferData(p0, snapshot, p2));
    }

    public static void glBufferData(int p0, java.nio.DoubleBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL15.glBufferData(p0, snapshot.asDoubleBuffer(), p2));
    }

    public static void glBufferData(int p0, java.nio.FloatBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL15.glBufferData(p0, snapshot.asFloatBuffer(), p2));
    }

    public static void glBufferData(int p0, java.nio.IntBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL15.glBufferData(p0, snapshot.asIntBuffer(), p2));
    }

    public static void glBufferData(int p0, java.nio.ShortBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL15.glBufferData(p0, snapshot.asShortBuffer(), p2));
    }

    public static void glBufferData(int p0, long p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL15.glBufferData(p0, p1, p2));
    }

    public static void glBufferSubData(int p0, long p1, java.nio.ByteBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL15.glBufferSubData(p0, p1, snapshot));
    }

    public static void glBufferSubData(int p0, long p1, java.nio.DoubleBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL15.glBufferSubData(p0, p1, snapshot.asDoubleBuffer()));
    }

    public static void glBufferSubData(int p0, long p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL15.glBufferSubData(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glBufferSubData(int p0, long p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL15.glBufferSubData(p0, p1, snapshot.asIntBuffer()));
    }

    public static void glBufferSubData(int p0, long p1, java.nio.ShortBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL15.glBufferSubData(p0, p1, snapshot.asShortBuffer()));
    }

    // 删除当前绑定的 buffer 会同步重置录制侧与执行侧的 ARRAY_BUFFER 绑定。
    public static void glDeleteBuffers(int p0) {
        if (BridgeSupport.pointerState().arrayBufferBinding() == p0) {
            BridgeSupport.pointerState().setArrayBufferBinding(0);
        }
        final String recordThread = Thread.currentThread().getName();
        BridgeSupport.enqueue(() -> {
            org.lwjgl.opengl.GL15.glDeleteBuffers(p0);
            if (BridgeSupport.executedArrayBufferBinding() == p0) {
                BridgeSupport.executedArrayBufferBinding(0, "GL15.glDeleteBuffers", recordThread);
            }
        });
    }

    // 删除当前绑定的 buffer 会同步重置录制侧与执行侧的 ARRAY_BUFFER 绑定。
    public static void glDeleteBuffers(java.nio.IntBuffer p0) {
        while (p0.hasRemaining()) {
            if (BridgeSupport.pointerState().arrayBufferBinding() == p0.get()) {
                BridgeSupport.pointerState().setArrayBufferBinding(0);
            }
        }
        p0.rewind();
        final String recordThread = Thread.currentThread().getName();
        BridgeSupport.enqueueSnapshot(p0, snapshot -> {
            final java.nio.IntBuffer view = snapshot.asIntBuffer();
            final int count = view.remaining();
            org.lwjgl.opengl.GL15.glDeleteBuffers(view);
            for (int i = 0; i < count; i++) {
                if (BridgeSupport.executedArrayBufferBinding() == view.get(i)) {
                    BridgeSupport.executedArrayBufferBinding(0, "GL15.glDeleteBuffers", recordThread);
                }
            }
        });
    }

    public static void glDeleteQueries(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL15.glDeleteQueries(p0));
    }

    public static void glDeleteQueries(java.nio.IntBuffer p0) {
        BridgeSupport.enqueueSnapshot(p0, snapshot -> org.lwjgl.opengl.GL15.glDeleteQueries(snapshot.asIntBuffer()));
    }

    public static void glEndQuery(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL15.glEndQuery(p0));
    }

    public static int glGenBuffers() {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL15.glGenBuffers());
    }

    public static void glGenBuffers(java.nio.IntBuffer p0) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.GL15.glGenBuffers(p0));
    }

    public static int glGenQueries() {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL15.glGenQueries());
    }

    public static void glGenQueries(java.nio.IntBuffer p0) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.GL15.glGenQueries(p0));
    }

    public static int glGetBufferParameter(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL15.glGetBufferParameter(p0, p1));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetBufferParameter(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.GL15.glGetBufferParameter(p0, p1, buf)));
    }

    public static int glGetBufferParameteri(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL15.glGetBufferParameteri(p0, p1));
    }

    public static java.nio.ByteBuffer glGetBufferPointer(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL15.glGetBufferPointer(p0, p1));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetBufferSubData(int p0, long p1, java.nio.ByteBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillBooleans(p2, 16, buf -> org.lwjgl.opengl.GL15.glGetBufferSubData(p0, p1, buf)));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetBufferSubData(int p0, long p1, java.nio.DoubleBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillDoubles(p2, 16, buf -> org.lwjgl.opengl.GL15.glGetBufferSubData(p0, p1, buf)));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetBufferSubData(int p0, long p1, java.nio.FloatBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p2, 16, buf -> org.lwjgl.opengl.GL15.glGetBufferSubData(p0, p1, buf)));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetBufferSubData(int p0, long p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.GL15.glGetBufferSubData(p0, p1, buf)));
    }

    public static void glGetBufferSubData(int p0, long p1, java.nio.ShortBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL15.glGetBufferSubData(p0, p1, p2));
    }

    public static int glGetQuery(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL15.glGetQuery(p0, p1));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetQuery(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.GL15.glGetQuery(p0, p1, buf)));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetQueryObject(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.GL15.glGetQueryObject(p0, p1, buf)));
    }

    public static int glGetQueryObjecti(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL15.glGetQueryObjecti(p0, p1));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetQueryObjectu(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.GL15.glGetQueryObjectu(p0, p1, buf)));
    }

    public static int glGetQueryObjectui(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL15.glGetQueryObjectui(p0, p1));
    }

    public static int glGetQueryi(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL15.glGetQueryi(p0, p1));
    }

    public static boolean glIsBuffer(int p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL15.glIsBuffer(p0));
    }

    public static boolean glIsQuery(int p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL15.glIsQuery(p0));
    }

    public static java.nio.ByteBuffer glMapBuffer(int p0, int p1, java.nio.ByteBuffer p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL15.glMapBuffer(p0, p1, p2));
    }

    public static java.nio.ByteBuffer glMapBuffer(int p0, int p1, long p2, java.nio.ByteBuffer p3) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL15.glMapBuffer(p0, p1, p2, p3));
    }

    public static boolean glUnmapBuffer(int p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL15.glUnmapBuffer(p0));
    }
}
