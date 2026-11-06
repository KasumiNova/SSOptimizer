package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import org.lwjgl.opengl.KHRDebugCallback;

import java.nio.IntBuffer;

public final class GL43 extends GL43Gen {
    private GL43() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    /** 回调在渲染线程触发。 */
    public static void glDebugMessageCallback(KHRDebugCallback callback) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glDebugMessageCallback(callback));
    }

    public static void glDebugMessageControl(int source, int type, int severity,
                                             IntBuffer ids, boolean enabled) {
        BridgeSupport.enqueueSnapshot(ids, snapshot ->
                org.lwjgl.opengl.GL43.glDebugMessageControl(source, type, severity,
                        snapshot.asIntBuffer(), enabled));
    }

    public static void glDispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glDispatchCompute(numGroupsX, numGroupsY, numGroupsZ));
    }

    public static void glInvalidateBufferData(int buffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glInvalidateBufferData(buffer));
    }


    public static void glMultiDrawArraysIndirect(int mode, java.nio.ByteBuffer indirect,
                                                 int drawcount, int stride) {
        BridgeSupport.enqueueSnapshot(indirect, snapshot ->
                org.lwjgl.opengl.GL43.glMultiDrawArraysIndirect(mode, snapshot, drawcount, stride));
    }

    public static void glMultiDrawArraysIndirect(int mode, IntBuffer indirect, int drawcount, int stride) {
        BridgeSupport.enqueueSnapshot(indirect, snapshot ->
                org.lwjgl.opengl.GL43.glMultiDrawArraysIndirect(mode, snapshot.asIntBuffer(), drawcount, stride));
    }

    public static void glMultiDrawArraysIndirect(int mode, long indirectOffset, int drawcount, int stride) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL43.glMultiDrawArraysIndirect(mode, indirectOffset, drawcount, stride));
    }

    public static void glMultiDrawElementsIndirect(int mode, int type, java.nio.ByteBuffer indirect,
                                                   int drawcount, int stride) {
        BridgeSupport.enqueueSnapshot(indirect, snapshot ->
                org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect(mode, type, snapshot, drawcount, stride));
    }

    public static void glMultiDrawElementsIndirect(int mode, int type, IntBuffer indirect,
                                                   int drawcount, int stride) {
        BridgeSupport.enqueueSnapshot(indirect, snapshot ->
                org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect(mode, type, snapshot.asIntBuffer(),
                        drawcount, stride));
    }

    public static void glMultiDrawElementsIndirect(int mode, int type, long indirectOffset,
                                                   int drawcount, int stride) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect(mode, type, indirectOffset, drawcount, stride));
    }


    public static void glShaderStorageBlockBinding(int program, int storageBlockIndex, int storageBlockBinding) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glShaderStorageBlockBinding(
                program, storageBlockIndex, storageBlockBinding));
    }

    public static void glTexBufferRange(int target, int internalFormat, int buffer, long offset, long size) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL43.glTexBufferRange(target, internalFormat, buffer, offset, size));
    }

    public static void glFramebufferParameteri(int target, int pname, int value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glFramebufferParameteri(target, pname, value));
    }

    public static void glInvalidateBufferSubData(int buffer, long offset, long length) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glInvalidateBufferSubData(buffer, offset, length));
    }

    public static void glInvalidateFramebuffer(int target, IntBuffer attachments) {
        BridgeSupport.enqueueSnapshot(attachments, snapshot ->
                org.lwjgl.opengl.GL43.glInvalidateFramebuffer(target, snapshot.asIntBuffer()));
    }

    public static void glInvalidateSubFramebuffer(int target, IntBuffer attachments,
                                                  int x, int y, int width, int height) {
        BridgeSupport.enqueueSnapshot(attachments, snapshot ->
                org.lwjgl.opengl.GL43.glInvalidateSubFramebuffer(target, snapshot.asIntBuffer(),
                        x, y, width, height));
    }

    public static void glInvalidateTexImage(int texture, int level) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glInvalidateTexImage(texture, level));
    }

    public static void glInvalidateTexSubImage(int texture, int level,
                                               int xOffset, int yOffset, int zOffset,
                                               int width, int height, int depth) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glInvalidateTexSubImage(
                texture, level, xOffset, yOffset, zOffset, width, height, depth));
    }

    public static void glTexStorage2DMultisample(int target, int samples, int internalFormat,
                                                 int width, int height, boolean fixedSampleLocations) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glTexStorage2DMultisample(
                target, samples, internalFormat, width, height, fixedSampleLocations));
    }

    public static void glTexStorage3DMultisample(int target, int samples, int internalFormat,
                                                 int width, int height, int depth, boolean fixedSampleLocations) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glTexStorage3DMultisample(
                target, samples, internalFormat, width, height, depth, fixedSampleLocations));
    }

    public static void glVertexBindingDivisor(int bindingindex, int divisor) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glVertexBindingDivisor(bindingindex, divisor));
    }
}
