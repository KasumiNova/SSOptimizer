package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * org.lwjgl.opengl.ARBVertexBufferObject 的 bridge 镜像（ARB 版 buffer 5 件套）。
 * <p>
 * 动机同 {@link GL11}。盘点结论：游戏本体仅 com.fs.graphics.SpriteBatch 使用
 * ARB 扩展路径，与 {@link GL15} 同语义、同功能别名，必须覆盖。buffer 数据参数
 * 的快照语义与 glGenBuffersARB 的阻塞通道语义同 {@link GL15}。
 */
public final class ARBVertexBufferObject extends ARBVertexBufferObjectGen {
    private ARBVertexBufferObject() {
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

    public static void glBindBufferARB(int target, int buffer) {
        BridgeSupport.simulatedState().onBindBuffer(target, buffer);
        final String recordThread;
        if (target == org.lwjgl.opengl.ARBVertexBufferObject.GL_ARRAY_BUFFER_ARB) {
            // 同 GL15.glBindBuffer：录制侧跟踪 ARRAY_BUFFER 绑定供 offset 指针重放恢复；
            // 同时捕获录制线程名供执行侧溯源环记录
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

    /** 单值形式与 {@link GL15#glGenBuffers()} 共享录制侧预生成 stash，命中时零阻塞。 */
    public static int glGenBuffersARB() {
        return BridgeSupport.acquireBufferId();
    }

    /** 渲染线程直接写入调用方 buffer；调用方阻塞期间 buffer 不被触碰。 */
    public static void glGenBuffersARB(IntBuffer buffers) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.ARBVertexBufferObject.glGenBuffersARB(buffers));
    }

    /** 仅指定容量的分配形式，无数据参数。 */
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
            // GL 规范：删除当前绑定的 buffer，其绑定重置为 0——录制侧跟踪同步
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
