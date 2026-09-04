package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * org.lwjgl.opengl.GL12 的 bridge 镜像（3D 纹理族 + glDrawRangeElements 族）。
 * <p>
 * 动机：BoxUtil 的 3D 纹理/数组纹理上传走 GL12 入口；BoxUtil 1.0.6 的
 * GLWrapper$Drawcall 以方法引用解析 glDrawRangeElements 全重载。语义与
 * {@link GL11} 一致：像素/索引数据在录制时刻深拷贝入池化快照，渲染线程执行后归还。
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

    // ------------------------------------------------------------------
    // 盘点补面：3D 纹理上传全变体（BoxUtil 1.6.0 GLWrapper$Texture 引用）。
    // 指针形无 buffer 可快照，指针指向的本地内存生命周期由调用方保证
    // （GL 契约语义）；buffer 形录制时刻快照入池。
    // ------------------------------------------------------------------

    /** 指针形 3D 纹理分配/上传。 */
    public static void glTexImage3D(int target, int level, int internalFormat,
                                    int width, int height, int depth,
                                    int border, int format, int type, long pixels) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL12.glTexImage3D(target, level,
                internalFormat, width, height, depth, border, format, type, pixels));
    }

    /** 像素数据录制时刻快照入队。 */
    public static void glTexImage3D(int target, int level, int internalFormat,
                                    int width, int height, int depth,
                                    int border, int format, int type, DoubleBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexImage3D(target, level, internalFormat,
                        width, height, depth, border, format, type, snapshot.asDoubleBuffer()));
    }

    /** 像素数据录制时刻快照入队。 */
    public static void glTexImage3D(int target, int level, int internalFormat,
                                    int width, int height, int depth,
                                    int border, int format, int type, FloatBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexImage3D(target, level, internalFormat,
                        width, height, depth, border, format, type, snapshot.asFloatBuffer()));
    }

    /** 像素数据录制时刻快照入队。 */
    public static void glTexImage3D(int target, int level, int internalFormat,
                                    int width, int height, int depth,
                                    int border, int format, int type, IntBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexImage3D(target, level, internalFormat,
                        width, height, depth, border, format, type, snapshot.asIntBuffer()));
    }

    /** 像素数据录制时刻快照入队。 */
    public static void glTexImage3D(int target, int level, int internalFormat,
                                    int width, int height, int depth,
                                    int border, int format, int type, ShortBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexImage3D(target, level, internalFormat,
                        width, height, depth, border, format, type, snapshot.asShortBuffer()));
    }

    /** 指针形 3D 纹理子区域上传。 */
    public static void glTexSubImage3D(int target, int level,
                                       int xOffset, int yOffset, int zOffset,
                                       int width, int height, int depth,
                                       int format, int type, long pixels) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL12.glTexSubImage3D(target, level,
                xOffset, yOffset, zOffset, width, height, depth, format, type, pixels));
    }

    /** 像素数据录制时刻快照入队。 */
    public static void glTexSubImage3D(int target, int level,
                                       int xOffset, int yOffset, int zOffset,
                                       int width, int height, int depth,
                                       int format, int type, ByteBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexSubImage3D(target, level,
                        xOffset, yOffset, zOffset, width, height, depth,
                        format, type, snapshot));
    }

    /** 像素数据录制时刻快照入队。 */
    public static void glTexSubImage3D(int target, int level,
                                       int xOffset, int yOffset, int zOffset,
                                       int width, int height, int depth,
                                       int format, int type, DoubleBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexSubImage3D(target, level,
                        xOffset, yOffset, zOffset, width, height, depth,
                        format, type, snapshot.asDoubleBuffer()));
    }

    /** 像素数据录制时刻快照入队。 */
    public static void glTexSubImage3D(int target, int level,
                                       int xOffset, int yOffset, int zOffset,
                                       int width, int height, int depth,
                                       int format, int type, IntBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexSubImage3D(target, level,
                        xOffset, yOffset, zOffset, width, height, depth,
                        format, type, snapshot.asIntBuffer()));
    }

    /** 像素数据录制时刻快照入队。 */
    public static void glTexSubImage3D(int target, int level,
                                       int xOffset, int yOffset, int zOffset,
                                       int width, int height, int depth,
                                       int format, int type, ShortBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot ->
                org.lwjgl.opengl.GL12.glTexSubImage3D(target, level,
                        xOffset, yOffset, zOffset, width, height, depth,
                        format, type, snapshot.asShortBuffer()));
    }

    // ------------------------------------------------------------------
    // glDrawRangeElements 族（BoxUtil 1.0.6 GLWrapper$Drawcall 引用；录制语义与
    // GL11.glDrawElements 一致：池化 DrawCommand 携带 pointer 快照组）
    // ------------------------------------------------------------------

    /** 索引 buffer 录制时刻快照，执行后归还池。 */
    public static void glDrawRangeElements(int mode, int start, int end, ByteBuffer indices) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setDrawRangeElementsSnapshot(mode, start, end, BridgeSupport.pool().snapshot(indices),
                DrawCommand.VIEW_BYTE, BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }

    /** 索引 buffer 录制时刻快照，执行后归还池。 */
    public static void glDrawRangeElements(int mode, int start, int end, IntBuffer indices) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setDrawRangeElementsSnapshot(mode, start, end, BridgeSupport.pool().snapshot(indices),
                DrawCommand.VIEW_INT, BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }

    /** 索引 buffer 录制时刻快照，执行后归还池。 */
    public static void glDrawRangeElements(int mode, int start, int end, ShortBuffer indices) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setDrawRangeElementsSnapshot(mode, start, end, BridgeSupport.pool().snapshot(indices),
                DrawCommand.VIEW_SHORT, BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }

    /** VBO 索引偏移形式：无 buffer 可快照。 */
    public static void glDrawRangeElements(int mode, int start, int end, int count, int type, long offset) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setDrawRangeElementsOffset(mode, start, end, count, type, offset,
                BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }
}
