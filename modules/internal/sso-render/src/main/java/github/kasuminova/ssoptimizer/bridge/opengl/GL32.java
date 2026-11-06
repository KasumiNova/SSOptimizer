package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.FrameFence;
import github.kasuminova.ssoptimizer.common.render.queue.FrameFenceImpl;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import github.kasuminova.ssoptimizer.common.render.queue.SignalFenceCommand;
import github.kasuminova.ssoptimizer.common.render.queue.WaitFenceCommand;

public final class GL32 extends GL32Gen {
    private GL32() {
    }

    private static volatile boolean auxWaitWithoutRealWarned;

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    /** 真实 sync 在信号序列点附着；帧失败时队列仍完成 fence，避免等待方永久悬挂。 */
    public static GLSync glFenceSync(int condition, int flags) {
        if (BridgeSupport.recordingContext().auxNative) {
            Object real = BridgeSupport.syncOps().fenceSync(condition, flags);
            FrameFence fence = new FrameFenceImpl();
            fence.signal();
            return new GLSync(fence, real);
        }
        FrameFence fence = new FrameFenceImpl();
        GLSync handle = new GLSync(fence);
        RenderQueue queue = BridgeSupport.queue();
        // 直交通道必须先落帧，确保 fence 覆盖此前已发出的挂起顶点流。
        BridgeSupport.flushVertexStream();
        queue.currentFrame().addFence(fence);
        queue.submit(new SignalFenceCommand(fence, () -> {
            Object real = BridgeSupport.syncOps().fenceSync(condition, flags);
            handle.attachReal(real);
            if (handle.isDeleted()) {
                // 信号前删除的句柄由信号命令随建随删，避免真实 sync 泄漏。
                BridgeSupport.syncOps().deleteSync(real);
            }
        }));
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "FENCE_SIG", System.identityHashCode(fence), 0, 0, null);
        return handle;
    }

    /** 主线程在 fence 放行后追加真实 glWaitSync；aux 原生线程在 sync 未附着时告警并跳过。 */
    public static void glWaitSync(GLSync sync, int flags, long timeout) {
        if (BridgeSupport.recordingContext().auxNative) {
            Object real = sync.realSync();
            if (real == null) {
                if (!auxWaitWithoutRealWarned) {
                    auxWaitWithoutRealWarned = true;
                    org.apache.log4j.Logger.getLogger(GL32.class).warn(
                            "[SSOptimizer] aux 线程 glWaitSync 遇到主产 fence 的真实 sync 未附着"
                                    + "（信号命令尚未执行），本次服务端等待跳过（恢复路径，稳态不应出现）");
                }
                return;
            }
            BridgeSupport.syncOps().waitSync(real, flags, timeout);
            return;
        }
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "FENCE_WAIT", System.identityHashCode(sync.fence()), 0, 0, null);
        BridgeSupport.enqueue(new WaitFenceCommand(sync.fence(), () -> {
            Object real = sync.realSync();
            if (real != null) {
                BridgeSupport.syncOps().waitSync(real, flags, timeout);
            }
        }));
    }

    /** 删除操作幂等；未附着的 sync 由信号命令随建随删。 */
    public static void glDeleteSync(GLSync sync) {
        sync.markDeleted();
        final Object real = sync.realSync();
        if (real == null) {
            return;
        }
        if (BridgeSupport.recordingContext().auxNative) {
            BridgeSupport.syncOps().deleteSync(real);
            return;
        }
        BridgeSupport.enqueue(() -> BridgeSupport.syncOps().deleteSync(real));
    }


    public static long glGetInteger64(int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL32.glGetInteger64(pname));
    }

    public static long glGetInteger64(int pname, int index) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL32.glGetInteger64(pname, index));
    }

    /** 小缓冲由 GetBufferFill 暂存，绕过 LWJGL2 固定下限检查。 */
    public static void glGetInteger64(int pname, java.nio.LongBuffer params) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillLongs(params, 16,
                buf -> org.lwjgl.opengl.GL32.glGetInteger64(pname, buf)));
    }

    public static void glGetInteger64(int pname, int index, java.nio.LongBuffer params) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillLongs(params, 16,
                buf -> org.lwjgl.opengl.GL32.glGetInteger64(pname, index, buf)));
    }


    /** latch 与真实等待共用同一超时预算；主线程的真实等待在渲染线程执行。 */
    public static int glClientWaitSync(GLSync sync, int flags, long timeout) {
        FrameFence fence = sync.fence();
        BridgeSupport.flushForFenceWait();
        final long deadline = System.nanoTime() + timeout;
        while (!fence.isSignaled()) {
            if (System.nanoTime() - deadline >= 0) {
                return org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED;
            }
            Thread.onSpinWait();
        }
        final Object real = sync.realSync();
        if (real == null) {
            // 帧失败强制 signal 的恢复路径：真实 sync 未创建，无 GPU 等待对象
            return org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED;
        }
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED;
        }
        if (BridgeSupport.recordingContext().auxNative) {
            return BridgeSupport.syncOps().clientWaitSync(real, flags, remaining);
        }
        final int[] result = new int[1];
        BridgeSupport.blockingWaitResource(
                () -> result[0] = BridgeSupport.syncOps().clientWaitSync(real, flags, remaining));
        return result[0];
    }

    /** GL_SYNC_STATUS 仅表示命令流已到达，不表示 GPU 已完成。 */
    public static int glGetSynci(GLSync sync, int pname) {
        switch (pname) {
            case org.lwjgl.opengl.GL32.GL_OBJECT_TYPE:
                return org.lwjgl.opengl.GL32.GL_SYNC_FENCE;
            case org.lwjgl.opengl.GL32.GL_SYNC_STATUS:
                return sync.fence().isSignaled()
                        ? org.lwjgl.opengl.GL32.GL_SIGNALED : org.lwjgl.opengl.GL32.GL_UNSIGNALED;
            case org.lwjgl.opengl.GL32.GL_SYNC_CONDITION:
                return org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE;
            case org.lwjgl.opengl.GL32.GL_SYNC_FLAGS:
                return 0;
            default:
                throw new IllegalArgumentException("[SSOptimizer] 不支持的 glGetSynci pname: 0x"
                        + Integer.toHexString(pname));
        }
    }

    /** 每个 pname 只写入一个值；length 非 null 时写入 1。 */
    public static void glGetSync(GLSync sync, int pname, java.nio.IntBuffer length,
                                 java.nio.IntBuffer values) {
        if (values == null || values.remaining() < 1) {
            throw new IllegalArgumentException("[SSOptimizer] glGetSync values 缓冲容量不足: "
                    + (values == null ? "null" : values.remaining()));
        }
        values.put(glGetSynci(sync, pname));
        if (length != null) {
            length.put(1);
        }
    }

    public static int glGetSync(GLSync sync, int pname) {
        return glGetSynci(sync, pname);
    }

    public static boolean glIsSync(GLSync sync) {
        return !sync.isDeleted();
    }


    public static void glDrawElementsBaseVertex(int mode, java.nio.ByteBuffer indices, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawElementsBaseVertex(mode, snapshot, baseVertex));
    }

    public static void glDrawElementsBaseVertex(int mode, java.nio.IntBuffer indices, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawElementsBaseVertex(mode, snapshot.asIntBuffer(), baseVertex));
    }

    public static void glDrawElementsBaseVertex(int mode, java.nio.ShortBuffer indices, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawElementsBaseVertex(mode, snapshot.asShortBuffer(), baseVertex));
    }

    public static void glDrawElementsBaseVertex(int mode, int count, int type, long indicesOffset, int baseVertex) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL32.glDrawElementsBaseVertex(mode, count, type, indicesOffset, baseVertex));
    }

    public static void glDrawRangeElementsBaseVertex(int mode, int start, int end,
                                                     java.nio.ByteBuffer indices, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawRangeElementsBaseVertex(mode, start, end, snapshot, baseVertex));
    }

    public static void glDrawRangeElementsBaseVertex(int mode, int start, int end,
                                                     java.nio.IntBuffer indices, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawRangeElementsBaseVertex(mode, start, end,
                        snapshot.asIntBuffer(), baseVertex));
    }

    public static void glDrawRangeElementsBaseVertex(int mode, int start, int end,
                                                     java.nio.ShortBuffer indices, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawRangeElementsBaseVertex(mode, start, end,
                        snapshot.asShortBuffer(), baseVertex));
    }

    public static void glDrawRangeElementsBaseVertex(int mode, int start, int end, int count, int type,
                                                     long indicesOffset, int baseVertex) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL32.glDrawRangeElementsBaseVertex(
                mode, start, end, count, type, indicesOffset, baseVertex));
    }

    public static void glDrawElementsInstancedBaseVertex(int mode, java.nio.ByteBuffer indices,
                                                         int primcount, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawElementsInstancedBaseVertex(mode, snapshot, primcount, baseVertex));
    }

    public static void glDrawElementsInstancedBaseVertex(int mode, java.nio.IntBuffer indices,
                                                         int primcount, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawElementsInstancedBaseVertex(mode, snapshot.asIntBuffer(),
                        primcount, baseVertex));
    }

    public static void glDrawElementsInstancedBaseVertex(int mode, java.nio.ShortBuffer indices,
                                                         int primcount, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawElementsInstancedBaseVertex(mode, snapshot.asShortBuffer(),
                        primcount, baseVertex));
    }

    public static void glDrawElementsInstancedBaseVertex(int mode, int count, int type, long indicesOffset,
                                                         int primcount, int baseVertex) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL32.glDrawElementsInstancedBaseVertex(
                mode, count, type, indicesOffset, primcount, baseVertex));
    }


    public static void glFramebufferTexture(int target, int attachment, int texture, int level) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL32.glFramebufferTexture(target, attachment, texture, level));
    }

    public static void glTexImage2DMultisample(int target, int samples, int internalformat,
                                               int width, int height, boolean fixedSampleLocations) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL32.glTexImage2DMultisample(
                target, samples, internalformat, width, height, fixedSampleLocations));
    }

    public static void glTexImage3DMultisample(int target, int samples, int internalformat,
                                               int width, int height, int depth, boolean fixedSampleLocations) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL32.glTexImage3DMultisample(
                target, samples, internalformat, width, height, depth, fixedSampleLocations));
    }

    public static void glSampleMaski(int maskNumber, int mask) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL32.glSampleMaski(maskNumber, mask));
    }
}
