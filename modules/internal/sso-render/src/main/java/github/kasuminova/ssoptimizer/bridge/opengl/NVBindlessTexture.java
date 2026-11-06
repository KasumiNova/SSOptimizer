package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

public final class NVBindlessTexture extends NVBindlessTextureGen {
    private NVBindlessTexture() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static long glGetTextureHandleNV(int texture) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.NVBindlessTexture.glGetTextureHandleNV(texture));
    }

    public static long glGetImageHandleNV(int texture, int level, boolean layered, int layer, int format) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.NVBindlessTexture
                .glGetImageHandleNV(texture, level, layered, layer, format));
    }

    public static boolean glIsTextureHandleResidentNV(long handle) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.NVBindlessTexture.glIsTextureHandleResidentNV(handle));
    }

    public static boolean glIsImageHandleResidentNV(long handle) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.NVBindlessTexture.glIsImageHandleResidentNV(handle));
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

    public static void glProgramUniformHandleuNV(int program, int location, LongBuffer values) {
        LongBuffer dup = values.duplicate();
        ByteBuffer bytes = ByteBuffer.allocateDirect(dup.remaining() * 8).order(ByteOrder.nativeOrder());
        bytes.asLongBuffer().put(dup);
        BridgeSupport.enqueueSnapshot(bytes, snapshot ->
                org.lwjgl.opengl.NVBindlessTexture.glProgramUniformHandleuNV(
                        program, location, snapshot.asLongBuffer()));
    }
}
