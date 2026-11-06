package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

public final class ARBDrawInstanced extends ARBDrawInstancedGen {
    private ARBDrawInstanced() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void glDrawArraysInstancedARB(int mode, int first, int count, int primcount) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.ARBDrawInstanced.glDrawArraysInstancedARB(mode, first, count, primcount));
    }

    public static void glDrawElementsInstancedARB(int mode, ByteBuffer indices, int primcount) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.ARBDrawInstanced.glDrawElementsInstancedARB(mode, snapshot, primcount));
    }

    public static void glDrawElementsInstancedARB(int mode, IntBuffer indices, int primcount) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.ARBDrawInstanced.glDrawElementsInstancedARB(mode, snapshot.asIntBuffer(), primcount));
    }

    public static void glDrawElementsInstancedARB(int mode, ShortBuffer indices, int primcount) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.ARBDrawInstanced.glDrawElementsInstancedARB(mode, snapshot.asShortBuffer(), primcount));
    }

    public static void glDrawElementsInstancedARB(int mode, int count, int type, long indicesOffset, int primcount) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.ARBDrawInstanced.glDrawElementsInstancedARB(mode, count, type, indicesOffset, primcount));
    }
}
