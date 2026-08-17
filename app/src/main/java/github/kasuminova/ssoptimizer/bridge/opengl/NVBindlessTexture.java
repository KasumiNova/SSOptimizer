package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

/**
 * org.lwjgl.opengl.NVBindlessTexture 的 bridge 镜像（NV 版 bindless 纹理句柄族）。
 * <p>
 * 与 {@link ARBBindlessTexture} 同语义（功能等价的厂商扩展对），BoxUtil 按
 * 驱动能力二选一，两个入口都必须覆盖。
 */
public final class NVBindlessTexture {
    private NVBindlessTexture() {
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

    /** 句柄获取：阻塞通道取回。 */
    public static long glGetTextureHandleNV(int texture) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVBindlessTexture.glGetTextureHandleNV(texture));
    }

    /** 句柄获取：阻塞通道取回。 */
    public static long glGetImageHandleNV(int texture, int level, boolean layered, int layer, int format) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVBindlessTexture
                .glGetImageHandleNV(texture, level, layered, layer, format));
    }

    /** 驻留查询：阻塞通道取回。 */
    public static boolean glIsTextureHandleResidentNV(long handle) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVBindlessTexture.glIsTextureHandleResidentNV(handle));
    }

    /** 驻留查询：阻塞通道取回。 */
    public static boolean glIsImageHandleResidentNV(long handle) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVBindlessTexture.glIsImageHandleResidentNV(handle));
    }

    public static void glMakeTextureHandleResidentNV(long handle) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVBindlessTexture.glMakeTextureHandleResidentNV(handle));
    }

    public static void glMakeTextureHandleNonResidentNV(long handle) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVBindlessTexture.glMakeTextureHandleNonResidentNV(handle));
    }

    public static void glMakeImageHandleResidentNV(long handle, int access) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVBindlessTexture.glMakeImageHandleResidentNV(handle, access));
    }

    public static void glMakeImageHandleNonResidentNV(long handle) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVBindlessTexture.glMakeImageHandleNonResidentNV(handle));
    }

    public static void glProgramUniformHandleui64NV(int program, int location, long value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVBindlessTexture
                .glProgramUniformHandleui64NV(program, location, value));
    }

    /** 句柄数组录制时刻快照入队（LongBuffer 先拷入原生序 ByteBuffer 再走快照池）。 */
    public static void glProgramUniformHandleuNV(int program, int location, LongBuffer values) {
        LongBuffer dup = values.duplicate();
        ByteBuffer bytes = ByteBuffer.allocateDirect(dup.remaining() * 8).order(ByteOrder.nativeOrder());
        bytes.asLongBuffer().put(dup);
        BridgeSupport.enqueueSnapshot(bytes, snapshot ->
                org.lwjgl.opengl.NVBindlessTexture.glProgramUniformHandleuNV(
                        program, location, snapshot.asLongBuffer()));
    }
}
