package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

/**
 * org.lwjgl.opengl.ARBBindlessTexture 的 bridge 镜像（bindless 纹理句柄族）。
 * <p>
 * 动机：BoxUtil 的 bindless 纹理路径走本族入口。句柄获取/驻留查询是返回值
 * 语义，走阻塞通道取回真实值；驻留状态变更换入队按提交序执行。
 */
public final class ARBBindlessTexture {
    private ARBBindlessTexture() {
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

    /** 句柄获取：阻塞通道取回（调用方立即消费返回值）。 */
    public static long glGetTextureHandleARB(int texture) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBBindlessTexture.glGetTextureHandleARB(texture));
    }

    /** 句柄获取：阻塞通道取回。 */
    public static long glGetImageHandleARB(int texture, int level, boolean layered, int layer, int format) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBBindlessTexture
                .glGetImageHandleARB(texture, level, layered, layer, format));
    }

    /** 驻留查询：阻塞通道取回。 */
    public static boolean glIsTextureHandleResidentARB(long handle) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBBindlessTexture.glIsTextureHandleResidentARB(handle));
    }

    /** 驻留查询：阻塞通道取回。 */
    public static boolean glIsImageHandleResidentARB(long handle) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBBindlessTexture.glIsImageHandleResidentARB(handle));
    }

    public static void glMakeTextureHandleResidentARB(long handle) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBBindlessTexture.glMakeTextureHandleResidentARB(handle));
    }

    public static void glMakeTextureHandleNonResidentARB(long handle) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBBindlessTexture.glMakeTextureHandleNonResidentARB(handle));
    }

    public static void glMakeImageHandleResidentARB(long handle, int access) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBBindlessTexture.glMakeImageHandleResidentARB(handle, access));
    }

    public static void glMakeImageHandleNonResidentARB(long handle) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBBindlessTexture.glMakeImageHandleNonResidentARB(handle));
    }

    public static void glProgramUniformHandleui64ARB(int program, int location, long value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBBindlessTexture
                .glProgramUniformHandleui64ARB(program, location, value));
    }

    /** 句柄数组录制时刻快照入队（LongBuffer 先拷入原生序 ByteBuffer 再走快照池）。 */
    public static void glProgramUniformHandleuARB(int program, int location, LongBuffer values) {
        LongBuffer dup = values.duplicate();
        ByteBuffer bytes = ByteBuffer.allocateDirect(dup.remaining() * 8).order(ByteOrder.nativeOrder());
        bytes.asLongBuffer().put(dup);
        BridgeSupport.enqueueSnapshot(bytes, snapshot ->
                org.lwjgl.opengl.ARBBindlessTexture.glProgramUniformHandleuARB(
                        program, location, snapshot.asLongBuffer()));
    }
}
