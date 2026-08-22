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
}
