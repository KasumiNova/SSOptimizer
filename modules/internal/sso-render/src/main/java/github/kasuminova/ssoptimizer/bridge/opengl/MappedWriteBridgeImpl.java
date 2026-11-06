package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.api.render.MappedWriteBridge;
import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl;
import github.kasuminova.ssoptimizer.common.render.queue.SuspendFrameException;
import org.apache.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** 有序写入仅在渲染线程执行；帧尾 fence 防止 CPU 写入与上一帧 GPU 读取竞争。 */
public final class MappedWriteBridgeImpl implements MappedWriteBridge {
    private static final Logger LOGGER = Logger.getLogger(MappedWriteBridgeImpl.class);

    private static final long FENCE_WAIT_TIMEOUT_NANOS = 1_000_000_000L;

    private static final MappedWriteBridgeImpl INSTANCE = new MappedWriteBridgeImpl();

    private static volatile boolean armed;
    private static volatile long fullyExecutedSeq = -1;
    private static Object latestFence;
    private static Object retiringFence;

    private MappedWriteBridgeImpl() {
    }

    public static MappedWriteBridgeImpl get() {
        return INSTANCE;
    }

    public static GlCommand frameEndMarker(final long frameSeq) {
        if (!armed) {
            return null;
        }
        return () -> publishFrameEnd(frameSeq);
    }

    private static void publishFrameEnd(final long frameSeq) {
        final RealSyncOps ops = BridgeSupport.syncOps();
        final Object previous = latestFence;
        latestFence = ops.fenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        fullyExecutedSeq = frameSeq;
        if (retiringFence != null) {
            ops.deleteSync(retiringFence);
        }
        retiringFence = previous;
    }

    private static void arm() {
        if (armed) {
            return;
        }
        synchronized (MappedWriteBridgeImpl.class) {
            if (armed) {
                return;
            }
            fullyExecutedSeq = Math.max(fullyExecutedSeq,
                    ((RenderQueueImpl) BridgeSupport.queue()).lastSubmittedSequence());
            armed = true;
        }
    }

    @Override
    public void submitOrderedWrite(final ByteBuffer mappedTarget, final IntBuffer source) {
        final RealMappingRegistry.Token token = RealMappingRegistry.tokenFor(mappedTarget);
        if (token == null) {
            // 未登记映射可能已释放，拒绝写入以避免访问失效地址。
            LOGGER.error("[SSOptimizer] 有序映射写入的目标未登记生命周期令牌，本次写入被拒绝：mapping@"
                    + Integer.toHexString(System.identityHashCode(mappedTarget)));
            return;
        }
        arm();
        final ByteBuffer snapshot = BridgeSupport.pool().snapshot(source);
        final long frameSeq = BridgeSupport.queue().currentFrame().sequence();
        BridgeSupport.queue().submit((GlCommand) () -> executeOrderedWrite(token, mappedTarget, snapshot, frameSeq));
    }

    private static void executeOrderedWrite(final RealMappingRegistry.Token token, final ByteBuffer mappedTarget,
                                            final ByteBuffer snapshot, final long frameSeq) {
        // 悬挂重试会复用同一快照，不能在重入判定前归还。
        if (fullyExecutedSeq < frameSeq - 1) {
            throw SuspendFrameException.INSTANCE;
        }
        try {
            if (!token.isValid()) {
                LOGGER.warn("[SSOptimizer] 有序映射写入的目标已失效（池扩容或销毁），本次写入跳过：vbo="
                        + token.bufferId());
                return;
            }
            final Object fence = latestFence;
            if (fence != null) {
                final int status = BridgeSupport.syncOps().clientWaitSync(fence, 0, FENCE_WAIT_TIMEOUT_NANOS);
                if (status == org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED
                        || status == org.lwjgl.opengl.GL32.GL_WAIT_FAILED) {
                    LOGGER.warn("[SSOptimizer] 有序映射写入的帧尾 fence 等待超时/失败（status=0x"
                            + Integer.toHexString(status) + "），本次写入跳过（下一计算周期自愈）");
                    return;
                }
            }
            final ByteBuffer target = mappedTarget.duplicate();
            target.position(0);
            target.limit(snapshot.remaining());
            target.put(snapshot);
            // 非相干持久映射需要显式建立 CPU 写入对 GPU 的可见性。
            BridgeSupport.syncOps().memoryBarrier(org.lwjgl.opengl.GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT);
        } finally {
            BridgeSupport.releaseSnapshot(snapshot);
        }
    }

    static void resetForTesting() {
        synchronized (MappedWriteBridgeImpl.class) {
            armed = false;
            fullyExecutedSeq = -1;
            latestFence = null;
            retiringFence = null;
        }
    }
}
