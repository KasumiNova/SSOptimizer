package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

public final class ARBBindlessTexture extends ARBBindlessTextureGen {
    private ARBBindlessTexture() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static long glGetTextureHandleARB(int texture) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBBindlessTexture.glGetTextureHandleARB(texture));
    }

    public static long glGetImageHandleARB(int texture, int level, boolean layered, int layer, int format) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBBindlessTexture
                .glGetImageHandleARB(texture, level, layered, layer, format));
    }

    public static boolean glIsTextureHandleResidentARB(long handle) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBBindlessTexture.glIsTextureHandleResidentARB(handle));
    }

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

    public static void glProgramUniformHandleuARB(int program, int location, LongBuffer values) {
        LongBuffer dup = values.duplicate();
        ByteBuffer bytes = ByteBuffer.allocateDirect(dup.remaining() * 8).order(ByteOrder.nativeOrder());
        bytes.asLongBuffer().put(dup);
        BridgeSupport.enqueueSnapshot(bytes, snapshot ->
                org.lwjgl.opengl.ARBBindlessTexture.glProgramUniformHandleuARB(
                        program, location, snapshot.asLongBuffer()));
    }
}
