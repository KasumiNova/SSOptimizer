package github.kasuminova.ssoptimizer.bridge.opengl;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.jctools.queues.MpmcUnboundedXaddArrayQueue;

/**
 * 一次 client pointer 设置（glVertexPointer/glColorPointer/glTexCoordPointer/
 * glNormalPointer/glInterleavedArrays）的录制快照。
 * <p>
 * 动机：LWJGL2 的 pointer 语义是「引用调用方 buffer 直到下一次 draw」，录制化
 * 之后渲染线程执行 draw 时源 buffer 可能已被改写，因此 pointer 调用当场把 buffer
 * 深拷贝进 {@link BridgeSupport#pool()} 快照，由随后的 draw 命令携带并在执行前
 * 重放（见 {@link PointerSnapshotGroup}）。
 * <p>
 * 与 FR ClientAttribTracker 的差异（后续演进点）：
 * <ul>
 *   <li>FR 在主线程侧仿真完整的 client attrib 状态机，draw 时只做增量重放；
 *       本阶段简化为「每次 draw 全量重放当前快照组」，重放开销换取实现简单；</li>
 *   <li>快照的引用计数包含「录制侧状态持有 1 份」（见 {@link #pendingDraws}），
 *       保证 pointer 设置一次、跨帧多次 draw 的常见路径不会在快照归还后复用
 *       已回收缓冲；代价是状态被覆盖前快照一直驻留（每线程每类 pointer 至多
 *       驻留一份，有界）。FR 的 tracker 可以在状态覆盖时精确释放，后续随
 *       tracker 仿真一并演进。</li>
 * </ul>
 * VBO 路径：pointer 的 long 偏移变体（VBO bound 时的语义）没有 buffer 可快照，
 * 记录为 {@link #data} 为 null 的偏移形式，重放时原样调用 long 变体。
 * 真实 GL 在 pointer 调用时刻捕获当时的 GL_ARRAY_BUFFER 绑定（LazyFont 等
 * 「绑定 VBO → 设 offset pointer → 解绑 → draw」序列依赖该语义），bridge 把
 * pointer 重放推迟到 draw 执行，因此偏移形式同时快照录制时刻的绑定
 * （{@link #vboId}），由 {@link PointerSnapshotGroup#apply()} 显式恢复。
 */
final class PointerSnapshot {
    /**
     * 快照对象池（借出：录制线程 ofBuffer/ofOffset；归还：最后一份引用释放时
     * 的 {@link #release()} 调用线程，渲染线程与录制线程皆可能）——MPMC 语义。
     * 动机：pointer 快照连同 pendingDraws 原子计数器是逐 pointer 调用的分配
     * （v50 cpu profile：PointerSnapshot.&lt;init&gt; + AtomicInteger.&lt;init&gt;
     * 合计约 600 样本），池化后稳态零分配。
     */
    private static final MpmcUnboundedXaddArrayQueue<PointerSnapshot> POOL =
            new MpmcUnboundedXaddArrayQueue<>(1024);

    /** pointer 种类：决定重放时调用哪一个真实 GL 入口。 */
    enum Kind {
        VERTEX, COLOR, TEX_COORD, NORMAL, INTERLEAVED
    }

    /** 以下字段均非 final：池化复用时由工厂方法整体重写（见 {@link #ofBuffer}）。 */
    Kind kind;
    /** glVertexPointer 的 size；INTERLEAVED 时复用为 format。 */
    int size;
    int type;
    int stride;
    /** 池化快照；VBO 偏移形式为 null。 */
    ByteBuffer data;
    /** VBO 偏移形式的有效偏移。 */
    long offset;
    /** VBO 偏移形式在录制时刻的 GL_ARRAY_BUFFER 绑定；buffer 形式为 0。 */
    int vboId;
    /**
     * 本快照的存活引用数：录制侧状态持有 1 份（创建时计数为 1），每条捕获它的
     * draw 命令再加 1；归零时归还池（data 缓冲与快照对象各自归还）。VBO 偏移
     * 形式（{@link #data} 为 null）无池缓冲，计数不参与归还。
     */
    private final AtomicInteger pendingDraws = new AtomicInteger();

    private PointerSnapshot() {
    }

    static PointerSnapshot ofBuffer(Kind kind, int size, int type, int stride, ByteBuffer data) {
        final PointerSnapshot snapshot = acquire();
        snapshot.kind = kind;
        snapshot.size = size;
        snapshot.type = type;
        snapshot.stride = stride;
        snapshot.data = data;
        snapshot.offset = 0;
        snapshot.vboId = 0;
        snapshot.pendingDraws.set(1);
        return snapshot;
    }

    static PointerSnapshot ofOffset(Kind kind, int size, int type, int stride, long offset, int vboId) {
        final PointerSnapshot snapshot = acquire();
        snapshot.kind = kind;
        snapshot.size = size;
        snapshot.type = type;
        snapshot.stride = stride;
        snapshot.data = null;
        snapshot.offset = offset;
        snapshot.vboId = vboId;
        snapshot.pendingDraws.set(0);
        return snapshot;
    }

    private static PointerSnapshot acquire() {
        final PointerSnapshot pooled = POOL.poll();
        return pooled != null ? pooled : new PointerSnapshot();
    }

    /** draw 命令捕获本快照时调用，推迟归还直到该 draw 执行完。 */
    void retain() {
        if (data != null) {
            pendingDraws.incrementAndGet();
        }
    }

    /**
     * 释放一份引用（draw 执行完或状态覆盖时调用）；最后一份引用释放时把快照
     * 缓冲与对象本身归还各自池。归还后本对象可被立即复用，调用方不得再触碰。
     */
    void release() {
        if (data != null && pendingDraws.decrementAndGet() == 0) {
            BridgeSupport.releaseSnapshot(data);
            data = null;
            POOL.offer(this);
        }
    }

    /** 在渲染线程重放本 pointer 设置（真实 GL 调用）。 */
    void apply() {
        switch (kind) {
            case VERTEX:
                if (data != null) {
                    org.lwjgl.opengl.GL11.glVertexPointer(size, type, stride, data);
                } else {
                    org.lwjgl.opengl.GL11.glVertexPointer(size, type, stride, offset);
                }
                break;
            case COLOR:
                if (data != null) {
                    org.lwjgl.opengl.GL11.glColorPointer(size, type, stride, data);
                } else {
                    org.lwjgl.opengl.GL11.glColorPointer(size, type, stride, offset);
                }
                break;
            case TEX_COORD:
                if (data != null) {
                    org.lwjgl.opengl.GL11.glTexCoordPointer(size, type, stride, data);
                } else {
                    org.lwjgl.opengl.GL11.glTexCoordPointer(size, type, stride, offset);
                }
                break;
            case NORMAL:
                if (data != null) {
                    org.lwjgl.opengl.GL11.glNormalPointer(type, stride, data);
                } else {
                    org.lwjgl.opengl.GL11.glNormalPointer(type, stride, offset);
                }
                break;
            case INTERLEAVED:
                org.lwjgl.opengl.GL11.glInterleavedArrays(size, stride, data);
                break;
            default:
                throw new IllegalStateException("[SSOptimizer] 未知 pointer 快照种类 " + kind);
        }
    }
}
