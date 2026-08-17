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
 * 深拷贝为池化快照（语义见 {@link GL11} 类 javadoc）；glGenBuffers 走阻塞通道
 * 取回真实 id（预生成 stash 为后续演进点）；查询/map 面本阶段不做。
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
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL15.glBindBuffer(target, buffer));
    }

    /** 资源分配：阻塞通道取回真实 buffer id。 */
    public static int glGenBuffers() {
        return BridgeSupport.blockingGet(org.lwjgl.opengl.GL15::glGenBuffers);
    }

    /** 渲染线程直接写入调用方 buffer；调用方阻塞期间 buffer 不被触碰。 */
    public static void glGenBuffers(IntBuffer buffers) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL15.glGenBuffers(buffers));
    }

    /** 仅指定容量的分配形式，无数据参数。 */
    public static void glBufferData(int target, long size, int usage) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL15.glBufferData(target, size, usage));
    }

    public static void glBufferData(int target, ByteBuffer data, int usage) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferData(target, snapshot, usage));
    }

    public static void glBufferData(int target, DoubleBuffer data, int usage) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferData(target, snapshot.asDoubleBuffer(), usage));
    }

    public static void glBufferData(int target, FloatBuffer data, int usage) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferData(target, snapshot.asFloatBuffer(), usage));
    }

    public static void glBufferData(int target, IntBuffer data, int usage) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferData(target, snapshot.asIntBuffer(), usage));
    }

    public static void glBufferData(int target, ShortBuffer data, int usage) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferData(target, snapshot.asShortBuffer(), usage));
    }

    public static void glBufferSubData(int target, long offset, ByteBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferSubData(target, offset, snapshot));
    }

    public static void glBufferSubData(int target, long offset, DoubleBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferSubData(target, offset, snapshot.asDoubleBuffer()));
    }

    public static void glBufferSubData(int target, long offset, FloatBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferSubData(target, offset, snapshot.asFloatBuffer()));
    }

    public static void glBufferSubData(int target, long offset, IntBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferSubData(target, offset, snapshot.asIntBuffer()));
    }

    public static void glBufferSubData(int target, long offset, ShortBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL15.glBufferSubData(target, offset, snapshot.asShortBuffer()));
    }

    public static void glDeleteBuffers(int buffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL15.glDeleteBuffers(buffer));
    }

    public static void glDeleteBuffers(IntBuffer buffers) {
        BridgeSupport.enqueueSnapshot(buffers, snapshot ->
                org.lwjgl.opengl.GL15.glDeleteBuffers(snapshot.asIntBuffer()));
    }

    /**
     * 解除 buffer 映射。map/unmap 跨线程语义：映射指针由 {@code glMapBuffer} 经阻塞通道
     * 取回后主线程直接写入，unmap 必须走阻塞通道 drain 到渲染线程真实执行后才返回，
     * 保证「主线程写完 → unmap」的先后顺序不被异步执行颠覆（模组低频路径）。
     *
     * @return 真实 glUnmapBuffer 的返回值（false 表示映射期间数据损坏）
     */
    public static boolean glUnmapBuffer(int target) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL15.glUnmapBuffer(target));
    }
}
