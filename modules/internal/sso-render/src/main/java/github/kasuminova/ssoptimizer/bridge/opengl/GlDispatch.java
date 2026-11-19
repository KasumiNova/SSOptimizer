package github.kasuminova.ssoptimizer.bridge.opengl;

import com.fs.graphics.util.GLListManager;
import github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPool;
import github.kasuminova.ssoptimizer.common.render.queue.DiscardableGlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.ProbeSiteCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.apache.log4j.Logger;

import java.util.concurrent.Callable;

public final class GlDispatch {
    private static final Logger LOGGER = Logger.getLogger(GlDispatch.class);
    private static final boolean RENDER_THREAD_ENABLED = RenderThreadMode.isEnabled();

    private GlDispatch() {
    }

    /** 接管命令参数快照；提交失败时立即归还。可在任意生产者线程调用。 */
    public static void submit(final GlCommand command) {
        try {
            BridgeSupport.flushVertexStream();
            BridgeSupport.queue().submit(command);
        } catch (RuntimeException | Error failure) {
            if (ProbeSiteCommand.unwrap(command) instanceof DiscardableGlCommand owned) {
                try {
                    owned.discard();
                } catch (RuntimeException | Error discardFailure) {
                    if (discardFailure != failure) {
                        failure.addSuppressed(discardFailure);
                    }
                    LOGGER.error("[SSOptimizer] 归还未提交的 GL 命令资源失败", discardFailure);
                }
            }
            throw failure;
        }
    }

    /** 专用绘制命令只能在主线程普通录制段提交。 */
    public static boolean canSubmitOwnedDraw() {
        if (!RENDER_THREAD_ENABLED || BridgeSupport.installedQueue() == null || !BridgeSupport.isMainRecordingThread()) {
            return false;
        }
        final RecordingContext context = BridgeSupport.recordingContext();
        return !context.auxNative && !context.isRecordingDisplayList() && !context.vertexStream.hasOpenSegment()
                && !GLListManager.buildingList;
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
