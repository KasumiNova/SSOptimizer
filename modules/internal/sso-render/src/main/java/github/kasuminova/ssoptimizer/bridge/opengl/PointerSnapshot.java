package github.kasuminova.ssoptimizer.bridge.opengl;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.jctools.queues.MpmcUnboundedXaddArrayQueue;

/** 在录制时深拷贝 client pointer 数据；偏移形式同时记录当时的 VBO 绑定。 */
final class PointerSnapshot {
    /** 录制线程与渲染线程均可归还，因此使用 MPMC 池。 */
    private static final MpmcUnboundedXaddArrayQueue<PointerSnapshot> POOL =
            new MpmcUnboundedXaddArrayQueue<>(1024);

    enum Kind {
        VERTEX, COLOR, TEX_COORD, NORMAL, INTERLEAVED
    }

    Kind kind;
    int size;
    int type;
    int stride;
    ByteBuffer data;
    long offset;
    int vboId;
    /** 录制侧状态持有一份引用，每条 draw 再持有一份；归零后归还缓冲与对象。 */
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

    void retain() {
        if (data != null) {
            pendingDraws.incrementAndGet();
        }
    }

    /** 最后一份引用释放后对象可立即复用，调用方不得再访问。 */
    void release() {
        if (data != null && pendingDraws.decrementAndGet() == 0) {
            BridgeSupport.releaseSnapshot(data);
            data = null;
            POOL.offer(this);
        }
    }

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
