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
 * BoxUtil 尾迹写入桥，在渲染写入服务可用时以 CPU 暂存缓冲隔离逻辑线程与 GPU 在飞绘制。
 * <p>同一池的双逻辑线程在屏障后返回，每轮仅提交一次完整快照。</p>
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

    private static final class ScratchSlot {
        private final ByteBuffer scratch;
        private final AtomicBoolean pendingUpload = new AtomicBoolean();

        ScratchSlot(final ByteBuffer mapped) {
            // 视图使用绝对下标，暂存缓冲必须保持与映射一致的容量和字节序。
            this.scratch = ByteBuffer.allocateDirect(mapped.capacity()).order(mapped.order());
        }

        IntBuffer view() {
            return scratch.asIntBuffer();
        }
    }

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

    public static void flushCompute() {
        flush(true);
    }

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

    /** 调用方必须持有 SLOTS 锁。 */
    private static void evictStaleKeys() {
        BufferKey stale;
        while ((stale = (BufferKey) STALE_KEYS.poll()) != null) {
            SLOTS.remove(stale);
        }
    }
}
