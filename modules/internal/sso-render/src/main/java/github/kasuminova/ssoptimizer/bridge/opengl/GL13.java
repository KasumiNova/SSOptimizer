package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

/**
 * org.lwjgl.opengl.GL13 的 bridge 镜像。
 * <p>
 * 动机同 {@link GL11}。盘点结论：游戏本体未用 GL13，GraphicsLib 的多纹理单元
 * （glActiveTexture，shader 前置）必须覆盖；压缩纹理上传
 * （glCompressedTexImage2D，BC 族 GPU 纹理压缩的落点，见
 * docs/design/gpu-texture-compression.md）随 T1 地基一并镜像。
 */
public final class GL13 {
    private GL13() {
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

    public static void glActiveTexture(int texture) {
        BridgeSupport.simulatedState().onActiveTexture(texture);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glActiveTexture(texture));
    }

    /**
     * 压缩纹理上传（BC 族）：入队快照语义同 {@link GL11#glTexImage2D}——快照在录制
     * 时刻深拷贝，调用方随后改写/复用源 buffer 不影响命令。与 LWJGL 一致，imageSize
     * 取 {@code data.remaining()}（快照尺寸），调用方须先把 limit 收到块字节数。
     * 同一纹理按级别顺序追加调用即可保持 mip 链上传顺序（命令按入队序执行）。
     */
    public static void glCompressedTexImage2D(int target, int level, int internalformat, int width, int height,
                                              int border, java.nio.ByteBuffer data) {
        BridgeSupport.simulatedState().onTexImage2D(target, level, internalformat, width, height);
        BridgeSupport.enqueueSnapshot(data, snapshot -> org.lwjgl.opengl.GL13.glCompressedTexImage2D(
                target, level, internalformat, width, height, border, snapshot));
    }

    // ------------------------------------------------------------------
    // 盘点补面：压缩纹理上传其余入口（BoxUtil 纹理压缩加载路径引用；
    // buffer 形态录制时刻快照，PBO 偏移形态传值——数据在绑定的
    // GL_PIXEL_UNPACK_BUFFER 里，无客户端内存可快照）
    // ------------------------------------------------------------------

    public static void glClientActiveTexture(int texture) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glClientActiveTexture(texture));
    }

    /** 像素数据录制时刻快照入队，imageSize 取快照尺寸（同 glCompressedTexImage2D）。 */
    public static void glCompressedTexImage1D(int target, int level, int internalformat, int width,
                                              int border, java.nio.ByteBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot -> org.lwjgl.opengl.GL13.glCompressedTexImage1D(
                target, level, internalformat, width, border, snapshot));
    }

    /** PBO 偏移形态：纯值参数，直接入队。 */
    public static void glCompressedTexImage1D(int target, int level, int internalformat, int width,
                                              int border, int imageSize, long dataOffset) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glCompressedTexImage1D(
                target, level, internalformat, width, border, imageSize, dataOffset));
    }

    /** PBO 偏移形态：纯值参数，直接入队。 */
    public static void glCompressedTexImage2D(int target, int level, int internalformat, int width, int height,
                                              int border, int imageSize, long dataOffset) {
        BridgeSupport.simulatedState().onTexImage2D(target, level, internalformat, width, height);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glCompressedTexImage2D(
                target, level, internalformat, width, height, border, imageSize, dataOffset));
    }

    /** 像素数据录制时刻快照入队。 */
    public static void glCompressedTexImage3D(int target, int level, int internalformat,
                                              int width, int height, int depth,
                                              int border, java.nio.ByteBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot -> org.lwjgl.opengl.GL13.glCompressedTexImage3D(
                target, level, internalformat, width, height, depth, border, snapshot));
    }

    /** PBO 偏移形态：纯值参数，直接入队。 */
    public static void glCompressedTexImage3D(int target, int level, int internalformat,
                                              int width, int height, int depth,
                                              int border, int imageSize, long dataOffset) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glCompressedTexImage3D(
                target, level, internalformat, width, height, depth, border, imageSize, dataOffset));
    }

    /** 像素数据录制时刻快照入队。 */
    public static void glCompressedTexSubImage1D(int target, int level, int xOffset, int width,
                                                 int format, java.nio.ByteBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot -> org.lwjgl.opengl.GL13.glCompressedTexSubImage1D(
                target, level, xOffset, width, format, snapshot));
    }

    /** PBO 偏移形态：纯值参数，直接入队。 */
    public static void glCompressedTexSubImage1D(int target, int level, int xOffset, int width,
                                                 int format, int imageSize, long dataOffset) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glCompressedTexSubImage1D(
                target, level, xOffset, width, format, imageSize, dataOffset));
    }

    /** 像素数据录制时刻快照入队。 */
    public static void glCompressedTexSubImage2D(int target, int level, int xOffset, int yOffset,
                                                 int width, int height, int format, java.nio.ByteBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot -> org.lwjgl.opengl.GL13.glCompressedTexSubImage2D(
                target, level, xOffset, yOffset, width, height, format, snapshot));
    }

    /** PBO 偏移形态：纯值参数，直接入队。 */
    public static void glCompressedTexSubImage2D(int target, int level, int xOffset, int yOffset,
                                                 int width, int height, int format, int imageSize, long dataOffset) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glCompressedTexSubImage2D(
                target, level, xOffset, yOffset, width, height, format, imageSize, dataOffset));
    }

    /** 像素数据录制时刻快照入队。 */
    public static void glCompressedTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset,
                                                 int width, int height, int depth, int format,
                                                 java.nio.ByteBuffer data) {
        BridgeSupport.enqueueSnapshot(data, snapshot -> org.lwjgl.opengl.GL13.glCompressedTexSubImage3D(
                target, level, xOffset, yOffset, zOffset, width, height, depth, format, snapshot));
    }

    /** PBO 偏移形态：纯值参数，直接入队。 */
    public static void glCompressedTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset,
                                                 int width, int height, int depth, int format,
                                                 int imageSize, long dataOffset) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glCompressedTexSubImage3D(
                target, level, xOffset, yOffset, zOffset, width, height, depth, format, imageSize, dataOffset));
    }

    /**
     * 压缩纹理读回（BoxUtil 引用，盘点补面）：渲染线程直接写入调用方 buffer；
     * 调用方阻塞期间 buffer 不被触碰（语义同 GL15.glGetBufferSubData 族）。
     */
    public static void glGetCompressedTexImage(int target, int level, java.nio.ByteBuffer data) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL13.glGetCompressedTexImage(target, level, data));
    }

    /** PBO 读回偏移形态：阻塞通道（读回语义强依赖执行完成）。 */
    public static void glGetCompressedTexImage(int target, int level, long dataOffset) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL13.glGetCompressedTexImage(target, level, dataOffset));
    }
}
