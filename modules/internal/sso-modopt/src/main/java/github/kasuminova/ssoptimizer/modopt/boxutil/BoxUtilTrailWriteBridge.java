package github.kasuminova.ssoptimizer.modopt.boxutil;

import github.kasuminova.ssoptimizer.api.render.MappedWriteBridge;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import org.apache.log4j.Logger;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BoxUtil 静态尾迹池写入的 RT 流序化桥（{@code StaticTrailMemoryPoolMixin} 的执行体）。
 * <p>
 * 机制：Mixin 把 {@code computeTrailNode}/{@code processCutTrailOnEntity} 内
 * 「映射缓冲.asIntBuffer()」改向到本类维护的等容 CPU 暂存缓冲（scratch）——逻辑线程的
 * 全部节点写入落在 scratch 上（纯 CPU 内存，与渲染线程/GPU 的在飞绘制无共享）；
 * 方法返回时把 scratch 全量经 {@link MappedWriteBridge} 排入渲染流，由渲染线程在
 * 上一帧帧尾 fence 通过后写回真实映射。写回是「快照 + 有序命令」，scratch 在提交后
 * 即可被下一计算周期覆写。
 * <p>
 * scratch 即尾迹池 CPU 侧的唯一事实源：自 Mixin 生效起所有写入都经它中转，因此
 * 全量上传不会丢数据（暂停/旁路的 trail 节点状态跨轮保留在 scratch 中）。
 * 池扩容重建映射（getMappingBuffer 返回新实例）时旧 scratch 随弱键回收、新 scratch
 * 从零开始——活跃 trail 每轮计算都会重写自身节点，旁路模式（战斗/生涯互斥）的
 * 节点在映射侧本就随池重建失效，无可见影响。
 * <p>
 * 双逻辑线程去重：{@code computeTrailNode} 由 BoxUtil 主/辅两个逻辑线程并发执行
 * （按 trail 下标分片，pool 级 SpinBarrier 会合），两个线程 RETURN 都会走到 flush——
 * 以 slot 的 pending 标志保证每池每轮只提交一次；先 RETURN 的线程提交时双方已过
 * barrier，scratch 内容完整。
 */
public final class BoxUtilTrailWriteBridge {
    private static final Logger LOGGER = Logger.getLogger(BoxUtilTrailWriteBridge.class);

    /** 已映射缓冲的弱身份键（ByteBuffer.equals 是内容语义，必须包一层身份弱键）。 */
    private static final class BufferKey extends WeakReference<ByteBuffer> {
        private final int identityHash;

        BufferKey(final ByteBuffer referent, final ReferenceQueue<ByteBuffer> queue) {
            super(referent, queue);
            this.identityHash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof BufferKey)) {
                return false;
            }
            final ByteBuffer self = get();
            return self != null && self == ((BufferKey) obj).get();
        }
    }

    /** 每个真实映射一份的暂存槽。 */
    private static final class ScratchSlot {
        private final ByteBuffer scratch;
        /** 本轮计算有写入待提交（双逻辑线程 RETURN 去重标志）。 */
        private final AtomicBoolean pendingUpload = new AtomicBoolean();

        ScratchSlot(final ByteBuffer mapped) {
            // 与映射等容同序：computeData 以绝对下标写视图，字节序须与映射一致
            // （allocateDirect 默认 BIG_ENDIAN，必须显式跟随映射序）
            this.scratch = ByteBuffer.allocateDirect(mapped.capacity()).order(mapped.order());
        }

        IntBuffer view() {
            return scratch.asIntBuffer();
        }
    }

    /** 一次写入方法调用内触碰过的（映射, 暂存槽）。 */
    private static final class Touched {
        private final ByteBuffer mapped;
        private final ScratchSlot slot;

        Touched(final ByteBuffer mapped, final ScratchSlot slot) {
            this.mapped = mapped;
            this.slot = slot;
        }
    }

    private static final ReferenceQueue<ByteBuffer> STALE_KEYS = new ReferenceQueue<>();
    private static final Map<BufferKey, ScratchSlot> SLOTS = new HashMap<>();
    private static final ThreadLocal<List<Touched>> TOUCHED = ThreadLocal.withInitial(ArrayList::new);

    private BoxUtilTrailWriteBridge() {
    }

    /**
     * {@code getMappingBuffer().asIntBuffer()} 的改向入口：RT 模式下返回 scratch 视图，
     * 非 RT 模式（{@link MappedWriteBridge} 未注册）原样返回真实映射视图（原版语义）。
     *
     * @param mapped 池的真实映射缓冲本体
     * @return 本轮写入的目标视图
     */
    public static IntBuffer scratchViewFor(final ByteBuffer mapped) {
        final MappedWriteBridge bridge = ServiceRegistry.getOrNull(MappedWriteBridge.class);
        if (bridge == null) {
            return mapped.asIntBuffer();
        }
        ScratchSlot slot;
        synchronized (SLOTS) {
            evictStaleKeys();
            slot = SLOTS.get(new BufferKey(mapped, null));
            if (slot == null) {
                slot = new ScratchSlot(mapped);
                SLOTS.put(new BufferKey(mapped, STALE_KEYS), slot);
                LOGGER.info("[SSOptimizer] BoxUtil 静态尾迹池暂存缓冲已建立：容量 "
                        + slot.scratch.capacity() + " 字节");
            }
        }
        slot.pendingUpload.set(true);
        TOUCHED.get().add(new Touched(mapped, slot));
        return slot.view();
    }

    /** {@code computeTrailNode} 返回点：每池每轮只提交一次（双逻辑线程去重）。 */
    public static void flushCompute() {
        flush(true);
    }

    /** {@code processCutTrailOnEntity} 返回点：单线程路径，逐槽提交。 */
    public static void flushCut() {
        flush(false);
    }

    private static void flush(final boolean dedupByPending) {
        final List<Touched> touched = TOUCHED.get();
        if (touched.isEmpty()) {
            return;
        }
        try {
            final MappedWriteBridge bridge = ServiceRegistry.getOrNull(MappedWriteBridge.class);
            if (bridge == null) {
                return;
            }
            for (final Touched entry : touched) {
                if (dedupByPending && !entry.slot.pendingUpload.getAndSet(false)) {
                    continue;
                }
                bridge.submitOrderedWrite(entry.mapped, entry.slot.view());
            }
        } finally {
            touched.clear();
        }
    }

    /** 回收键已死的槽（池销毁/扩容后旧映射被 GC 时）。调用方必须持有 SLOTS 锁。 */
    private static void evictStaleKeys() {
        BufferKey stale;
        while ((stale = (BufferKey) STALE_KEYS.poll()) != null) {
            SLOTS.remove(stale);
        }
    }
}
