package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * org.lwjgl.opengl.GL15 的 bridge 镜像（buffer 对象 5 件套）。
 * <p>
 * 动机同 {@link GL11}。盘点结论：SSOptimizer 自身 DynamicVbo（环形 VBO）走
 * GL15 全套，必须覆盖。buffer 数据参数（glBufferData/glBufferSubData）录制时
 * 深拷贝为池化快照（语义见 {@link GL11} 类 javadoc）；glGenBuffers 单值形式
 * 走录制侧预生成 stash（{@link BridgeSupport#acquireBufferId()}，命中零阻塞，
 * 空时一次阻塞批量补货 64 个），批量形式仍阻塞直通；查询/map 面本阶段不做。
 */
public final class GL15 {
    private GL15() {
    }

    /**
     * 安装命令消费者，语义同 {@link GL11#install(RenderQueue)}。
     *
     * @param renderQueue 渲染队列实例
     */
    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    /** 测试用：卸载已安装的队列，避免用例间静态状态串扰。 */
    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glBindBuffer(int target, int buffer) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "BIND_BUF", target, buffer, 0, null);
        BufferMapEmulator.onBindBuffer(target, buffer);
        BridgeSupport.simulatedState().onBindBuffer(target, buffer);
        if (target == org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER) {
            // 录制侧跟踪 ARRAY_BUFFER 绑定：offset 形式的 client pointer 调用
            // 在真实 GL 中会捕获调用时刻的绑定（LazyFont 等「绑定→设 pointer→解绑→
            // draw」序列依赖该语义），bridge 把 pointer 重放推迟到 draw 时必须
            // 显式恢复该绑定（见 PointerSnapshotGroup.apply）
            BridgeSupport.pointerState().setArrayBufferBinding(buffer);
        }
        BridgeSupport.enqueue(() -> {
            org.lwjgl.opengl.GL15.glBindBuffer(target, buffer);
            if (target == org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER) {
                // 渲染线程簿记：pointer 快照重放据此恢复录制时刻的绑定
                BridgeSupport.executedArrayBufferBinding(buffer);
            }
        });
    }

    /** 单值形式走录制侧预生成 stash（{@link BridgeSupport#acquireBufferId()}），命中时零阻塞。 */
    public static int glGenBuffers() {
        return BridgeSupport.acquireBufferId();
    }

    /** 渲染线程直接写入调用方 buffer；调用方阻塞期间 buffer 不被触碰。 */
    public static void glGenBuffers(IntBuffer buffers) {
        BridgeSupport.blockingWaitResource(() -> {
            org.lwjgl.opengl.GL15.glGenBuffers(buffers);
            // 同 acquireBufferId 的 fail-fast：真实批发静默出 0 时在生成点拦截
            // （诊断语义见 BridgeSupport.validateGeneratedBufferIds）。批量形式
            // 非热路径，拷贝校验开销可忽略。
            int[] written = new int[buffers.remaining()];
            buffers.slice().get(written);
            BridgeSupport.validateGeneratedBufferIds(written, org.lwjgl.opengl.GL11::glGetError);
        });
    }

    /** 仅指定容量的分配形式，无数据参数。 */
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
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL15.glDeleteBuffers(buffer));
    }

    public static void glDeleteBuffers(IntBuffer buffers) {
        while (buffers.hasRemaining()) {
            BufferMapEmulator.onDeleteBuffer(buffers.get());
        }
        buffers.rewind();
        BridgeSupport.enqueueSnapshot(buffers, snapshot ->
                org.lwjgl.opengl.GL15.glDeleteBuffers(snapshot.asIntBuffer()));
    }

    /**
     * 解除 buffer 映射。纯写仿真映射（{@link BufferMapEmulator}）在此把写入区间快照
     * 入队上传后直接返回，零阻塞；非仿真映射（map 经阻塞通道取得真实指针）仍走
     * 阻塞通道 drain，保证「主线程写完 → unmap」的先后顺序不被异步执行颠覆。
     *
     * @return 真实 glUnmapBuffer 的返回值（false 表示映射期间数据损坏）；仿真映射恒 true
     */
    public static boolean glUnmapBuffer(int target) {
        BufferMapEmulator.PendingUpload upload = BufferMapEmulator.pollEmulatedUnmap(target);
        if (upload != null) {
            BufferMapEmulator.enqueueUpload(upload);
            return true;
        }
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace("UNMAP_REAL", target, 0, 0, null);
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL15.glUnmapBuffer(target));
    }

    // ------------------------------------------------------------------
    // 盘点补面：glGetBufferSubData 读回族（BoxUtil 引用）。
    // 渲染线程直接写入调用方 buffer；调用方阻塞期间 buffer 不被触碰
    // （语义同 glGenBuffers(IntBuffer) 的批量变体）。
    // ------------------------------------------------------------------

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
