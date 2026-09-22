package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.api.render.MappedWriteBridge;
import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl;
import github.kasuminova.ssoptimizer.common.render.queue.SuspendFrameException;
import org.apache.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * {@link MappedWriteBridge} 的渲染线程侧实现：把「对持久映射缓冲的 CPU 写入」封装为
 * 命令排入渲染流，使写入相对绘制命令获得流序与 GPU 序。
 * <p>
 * 帧尾 fence：挂载后每帧提交时由 {@link RenderQueueImpl} 在帧尾追加一条标记命令，
 * 渲染线程执行到它（= 本帧全部命令已进入 GPU 队列）即创建真实 fence sync 并发布到
 * {@link #latestFence}。有序写入命令执行时先对该 fence 做 clientWaitSync——GPU 侧
 * 上一帧的 VBO 读全部结束才落笔，消除「逻辑线程直写映射内存 vs 渲染线程执行上一帧
 * 绘制」的跨帧竞争（BoxUtil 1.6.0 静态尾迹撕裂成大方块三角形的根因）。
 * <p>
 * 悬挂协同：上一帧若因 fence 等待悬挂（续跑任务尚未排空），其帧尾标记可能晚于本帧
 * 命令执行——写入命令以 {@link #fullyExecutedSeq} 帧序号判定此情形并抛
 * {@link SuspendFrameException}，让本帧悬挂重排到上一帧续跑之后，流序不被颠覆。
 * <p>
 * 线程模型：{@link #submitOrderedWrite} 由录制侧（主线程/aux 生产者线程）并发调用；
 * 标记命令与写入命令的执行体只在渲染线程上运行，{@link #latestFence}/{@link #retiringFence}
 * 的读写因此天然串行（仅 {@link #fullyExecutedSeq} 存在挂载期的跨线程写，volatile 兜底）。
 * fence 生命周期：{@link #publishFrameEnd} 发布新 fence 时删除上上一代——写入命令总在
 * 自身帧内执行、只引用最新 fence，删除点（隔一代）之前所有可能引用它的命令都已执行完。
 */
public final class MappedWriteBridgeImpl implements MappedWriteBridge {
    private static final Logger LOGGER = Logger.getLogger(MappedWriteBridgeImpl.class);

    /** 帧尾 fence 的 clientWaitSync 超时：GPU 落后超过 1s 即渲染管线已严重异常，跳过写入并警告（下一计算周期自愈）。 */
    private static final long FENCE_WAIT_TIMEOUT_NANOS = 1_000_000_000L;

    private static final MappedWriteBridgeImpl INSTANCE = new MappedWriteBridgeImpl();

    /** 是否有写入方在用本通道（首个 submit 挂载；未挂载时帧尾不插 fence，零开销）。 */
    private static volatile boolean armed;
    /** 已执行到帧尾标记的帧序号（渲染线程写；挂载期由挂载线程写一次）。 */
    private static volatile long fullyExecutedSeq = -1;
    /** 最新一帧的帧尾真实 sync（仅渲染线程读写）。 */
    private static Object latestFence;
    /** 上一代帧尾 sync，下一代发布时删除（仅渲染线程读写）。 */
    private static Object retiringFence;

    private MappedWriteBridgeImpl() {
    }

    public static MappedWriteBridgeImpl get() {
        return INSTANCE;
    }

    /**
     * 帧尾标记钩子（{@link RenderQueueImpl} 帧提交时调用）。
     *
     * @return 本帧的帧尾标记命令；未挂载（尚无写入方）时返回 null，帧尾零开销
     */
    public static GlCommand frameEndMarker(final long frameSeq) {
        if (!armed) {
            return null;
        }
        return () -> publishFrameEnd(frameSeq);
    }

    /** 渲染线程执行到帧尾标记：本帧命令已全部进入 GPU 队列，创建并发布真实 fence。 */
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

    /**
     * 挂载通道：首个写入方到来时调用。幂等。
     * <p>
     * 初始化 {@link #fullyExecutedSeq} 为当前已提交帧序号：挂载前的帧没有帧尾标记，
     * 但也必然没有任何经本通道的写入所对应的绘制（写入方尚未存在），悬挂判定对
     * 这些帧无对象可保护，直接视为已执行完。
     */
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
            // 映射未经 bridge 真实映射路径登记 = 生命周期不可校验，写入可能在池扩容
            // unmap 后触达已释放内存（SIGSEGV）。拒绝写入；调用方下一计算周期会重试
            LOGGER.error("[SSOptimizer] 有序映射写入的目标未登记生命周期令牌，本次写入被拒绝：mapping@"
                    + Integer.toHexString(System.identityHashCode(mappedTarget)));
            return;
        }
        arm();
        final ByteBuffer snapshot = BridgeSupport.pool().snapshot(source);
        final long frameSeq = BridgeSupport.queue().currentFrame().sequence();
        BridgeSupport.queue().submit((GlCommand) () -> executeOrderedWrite(token, mappedTarget, snapshot, frameSeq));
    }

    /** 渲染线程执行体：帧序闸门 → 令牌复查 → 帧尾 fence 等待 → 落笔 → 内存屏障。 */
    private static void executeOrderedWrite(final RealMappingRegistry.Token token, final ByteBuffer mappedTarget,
                                            final ByteBuffer snapshot, final long frameSeq) {
        // 帧序闸门必须在快照归还的 finally 之外：悬挂后命令会作为续跑任务用同一快照
        // 重试，此处归还会造成同一实例双重入池（别名污染）与重试窗口内的数据覆写
        if (fullyExecutedSeq < frameSeq - 1) {
            // 上一帧悬挂未排完（其帧尾标记/绘制在续跑任务里，队列序上排在本帧之后）：
            // 悬挂本帧，让上一帧续跑先执行，恢复时重新判定
            throw SuspendFrameException.INSTANCE;
        }
        try {
            if (!token.isValid()) {
                // 池扩容/销毁已释放目标映射：跳过（映射内容随池重建，写入方下一
                // 计算周期重写全量数据自愈）
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
            // 非相干持久映射：CPU 写对后续 GPU 命令的可见性必须由本上下文的屏障建立
            BridgeSupport.syncOps().memoryBarrier(org.lwjgl.opengl.GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT);
        } finally {
            BridgeSupport.releaseSnapshot(snapshot);
        }
    }

    /** 测试用：重置挂载态与 fence 槽，避免用例间静态状态串扰。 */
    static void resetForTesting() {
        synchronized (MappedWriteBridgeImpl.class) {
            armed = false;
            fullyExecutedSeq = -1;
            latestFence = null;
            retiringFence = null;
        }
    }
}
