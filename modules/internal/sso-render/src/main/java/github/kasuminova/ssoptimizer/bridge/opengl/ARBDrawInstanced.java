package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * org.lwjgl.opengl.ARBDrawInstanced 的 bridge 镜像（实例化绘制 ARB 变体）。
 * <p>
 * 动机：GL31 实例化绘制的 ARB 扩展别名（LWJGL2 中同功能），模组按能力探测
 * 二选一，两个入口都必须覆盖。索引 buffer 参数在录制时刻快照
 * （防调用方随后改写）；VBO 偏移形态传值即可。
 */
public final class ARBDrawInstanced {
    private ARBDrawInstanced() {
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

    public static void glDrawArraysInstancedARB(int mode, int first, int count, int primcount) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.ARBDrawInstanced.glDrawArraysInstancedARB(mode, first, count, primcount));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsInstancedARB(int mode, ByteBuffer indices, int primcount) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.ARBDrawInstanced.glDrawElementsInstancedARB(mode, snapshot, primcount));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsInstancedARB(int mode, IntBuffer indices, int primcount) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.ARBDrawInstanced.glDrawElementsInstancedARB(mode, snapshot.asIntBuffer(), primcount));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsInstancedARB(int mode, ShortBuffer indices, int primcount) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.ARBDrawInstanced.glDrawElementsInstancedARB(mode, snapshot.asShortBuffer(), primcount));
    }

    /** VBO 偏移形态：纯值参数，直接入队。 */
    public static void glDrawElementsInstancedARB(int mode, int count, int type, long indicesOffset, int primcount) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.ARBDrawInstanced.glDrawElementsInstancedARB(mode, count, type, indicesOffset, primcount));
    }
}
