package github.kasuminova.ssoptimizer.bridge.opengl;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

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
 */
final class PointerSnapshot {
    /** pointer 种类：决定重放时调用哪一个真实 GL 入口。 */
    enum Kind {
        VERTEX, COLOR, TEX_COORD, NORMAL, INTERLEAVED
    }

    final Kind kind;
    /** glVertexPointer 的 size；INTERLEAVED 时复用为 format。 */
    final int size;
    final int type;
    final int stride;
    /** 池化快照；VBO 偏移形式为 null。 */
    final ByteBuffer data;
    /** VBO 偏移形式的有效偏移。 */
    final long offset;
    /**
     * 本快照的存活引用数：录制侧状态持有 1 份（创建时计数为 1），每条捕获它的
     * draw 命令再加 1；归零时归还池。VBO 偏移形式（{@link #data} 为 null）
     * 无池缓冲，计数不参与归还。
     */
    private final AtomicInteger pendingDraws;

    private PointerSnapshot(Kind kind, int size, int type, int stride, ByteBuffer data, long offset) {
        this.kind = kind;
        this.size = size;
        this.type = type;
        this.stride = stride;
        this.data = data;
        this.offset = offset;
        this.pendingDraws = new AtomicInteger(data != null ? 1 : 0);
    }

    static PointerSnapshot ofBuffer(Kind kind, int size, int type, int stride, ByteBuffer data) {
        return new PointerSnapshot(kind, size, type, stride, data, 0);
    }

    static PointerSnapshot ofOffset(Kind kind, int size, int type, int stride, long offset) {
        return new PointerSnapshot(kind, size, type, stride, null, offset);
    }

    /** draw 命令捕获本快照时调用，推迟归还直到该 draw 执行完。 */
    void retain() {
        if (data != null) {
            pendingDraws.incrementAndGet();
        }
    }

    /**
     * 释放一份引用（draw 执行完或状态覆盖时调用）；最后一份引用释放时把快照
     * 归还池。
     */
    void release() {
        if (data != null && pendingDraws.decrementAndGet() == 0) {
            BridgeSupport.releaseSnapshot(data);
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
