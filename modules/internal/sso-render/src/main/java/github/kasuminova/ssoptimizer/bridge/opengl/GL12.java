package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * org.lwjgl.opengl.GL12 的 bridge 镜像（3D 纹理族）。
 * <p>
 * 动机：BoxUtil 的 3D 纹理/数组纹理上传走 GL12 入口。语义与 {@link GL11} 一致：
 * 像素数据在录制时刻深拷贝入池化快照，渲染线程执行后归还。
 */
public final class GL12 {
    private GL12() {
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

    /** 像素数据录制时刻快照入队。 */
    public static void glTexImage3D(int target, int level, int internalFormat,
                                    int width, int height, int depth,
                                    int border, int format, int type, ByteBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexImage3D(target, level, internalFormat,
                        width, height, depth, border, format, type, snapshot));
    }

    /** 像素数据录制时刻快照入队。 */
    public static void glTexSubImage3D(int target, int level,
                                       int xOffset, int yOffset, int zOffset,
                                       int width, int height, int depth,
                                       int format, int type, FloatBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexSubImage3D(target, level,
                        xOffset, yOffset, zOffset, width, height, depth,
                        format, type, snapshot.asFloatBuffer()));
    }
}
