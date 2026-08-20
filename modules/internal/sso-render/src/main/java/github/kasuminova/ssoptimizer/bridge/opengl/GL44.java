package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * org.lwjgl.opengl.GL44 的 bridge 镜像（不可变缓冲存储族）。
 * <p>
 * 动机：BoxUtil 的持久映射缓冲分配走 GL44 glBufferStorage。语义同 {@link GL11}：
 * 初始数据在录制时刻深拷贝入池化快照。
 */
public final class GL44 {
    private GL44() {
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

    public static void glBufferStorage(int target, long size, int flags) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL44.glBufferStorage(target, size, flags));
    }

    /** 初始数据录制时刻快照入队。 */
    public static void glBufferStorage(int target, ByteBuffer data, int flags) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL44.glBufferStorage(target, snapshot, flags));
    }

    /** 初始数据录制时刻快照入队。 */
    public static void glBufferStorage(int target, FloatBuffer data, int flags) {
        BridgeSupport.enqueueSnapshot(data, snapshot ->
                org.lwjgl.opengl.GL44.glBufferStorage(target, snapshot.asFloatBuffer(), flags));
    }
}
