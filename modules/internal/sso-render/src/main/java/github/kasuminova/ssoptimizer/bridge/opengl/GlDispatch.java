package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPool;
import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.util.concurrent.Callable;

public final class GlDispatch {

    private GlDispatch() {
    }

    /** 可从任意生产者线程调用；命令不得引用录制后仍可变的数据。 */
    public static void submit(final GlCommand command) {
        BridgeSupport.flushVertexStream();
        BridgeSupport.queue().submit(command);
    }

    /** 归还当前绘制引用的资源前，将已录制顶点加入渲染队列。 */
    public static void flushRecordedVertices() {
        BridgeSupport.flushVertexStream();
    }

    /** 非渲染线程调用会先提交当前录制帧并阻塞等待结果，渲染线程调用则直接执行。 */
    public static <T> T allocate(final Callable<T> resourceTask) {
        return BridgeSupport.blockingGetResource(resourceTask);
    }

    /** 队列未安装时返回 false。 */
    public static boolean isRenderThread() {
        final RenderQueue queue = BridgeSupport.installedQueue();
        return queue != null && queue.isRenderThread();
    }

    public static boolean isMainRecordingThread() {
        return BridgeSupport.isMainRecordingThread();
    }

    /** 回调线程不定且必须线程安全；单个监听器异常不影响其他监听器。 */
    public static void registerContextRecreatedListener(final Runnable listener) {
        BridgeSupport.registerContextRecreatedListener(listener);
    }

    public static BufferSnapshotPool snapshotPool() {
        return BridgeSupport.snapshotPool();
    }

    /** native 等绕过合并器的 GL 调用后必须调用，仅渲染线程可用。 */
    public static void onExternalGlStateChange() {
        VertexBatchCommand.onExternalGlStateChange();
    }
}
