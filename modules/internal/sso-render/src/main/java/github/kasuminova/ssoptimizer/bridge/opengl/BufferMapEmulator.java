package github.kasuminova.ssoptimizer.bridge.opengl;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * buffer 映射写路径仿真：纯写映射（MAP_WRITE 且无 MAP_READ）不再走阻塞通道取真实
 * 映射指针，而是发还录制侧每 VBO 的直接镜像缓冲；unmap 时把写入区间快照成字节数组，
 * 随帧命令流入队到渲染线程做真实 {@code glBufferSubData} 上传。
 * <p>
 * 动机：BoxUtil TrailEntity.submitNodes 等模组路径每帧对 GL_TEXTURE_BUFFER 做
 * {@code glMapBufferRange(WRITE|INVALIDATE[_RANGE|BUFFER][|UNSYNCHRONIZED])} 写映射——
 * 真实映射是一次全管线 drain（StallDetector 熔断的 top1 站点），而纯写+invalidate
 * 语义下调用方根本不读旧内容，镜像缓冲在语义上完全等价。
 * <p>
 * 内存安全：unmap 即快照（byte[] 拷贝），同一 VBO 跨帧重复映射复用镜像不产生跨帧
 * 数据竞争；上传任务在渲染线程先恢复/还原目标绑定，不污染录制侧绑定簿记。
 * <p>
 * 非仿真情形（含 MAP_READ、同线程同 target 重入映射、未跟踪的 target、未绑定 VBO）
 * 返回 null，由桥接回退真实阻塞映射——语义正确性优先于零阻塞。
 * <p>
 * 在途映射按（线程, target）为键隔离：BoxUtil 折叠进主帧后，其 logical/logical-aux/
 * rendering/主线程会在同一 GL target 上并发持有在途写映射（如多线程同时向不同 SSBO
 * 的 glMapBufferRange(37074, ...)——BoxUtil 仅以每 InstanceType 一把 GPU 锁互斥同池，
 * 跨池并发是设计内行为）。若按 target 单槽登记，unmap 配对会跨线程错位：先到的一方
 * 的 unmap 会消费另一方的在途登记，把另一方尚在写入的镜像提前快照上传（实例数据/节点
 * 数据部分为旧值或零），真实映射的一方反而丢失 unmap。按线程隔离后各方快照均在
 * 本线程写完之后产生，语义与非 RT 的多上下文模型等价。同理，绑定簿记也按
 * （线程, target）隔离：非 RT 下各 BoxUtil 线程各有独立 GL 上下文、绑定互不可见；
 * 折叠进单命令流后若共用一份绑定簿记，线程 A「bind SSBO-1」与线程 B「bind SSBO-2」
 * 交错会让 A 随后的 map 被错误归属到 SSBO-2 的镜像（A 的数据上传到 B 的池）。
 */
final class BufferMapEmulator {
    /** 录制侧簿记：（线程, target）复合键 → 该线程当前绑定的 VBO 名。 */
    private static final Map<Long, Integer> BOUND = new HashMap<>();
    /** 录制侧簿记：VBO 名 → 最近 glBufferData 容量（字节）。 */
    private static final Map<Integer, Integer> SIZES = new HashMap<>();
    /** 录制侧镜像：VBO 名 → 直接缓冲（按需增长复用）。 */
    private static final Map<Integer, ByteBuffer> MIRRORS = new HashMap<>();
    /** 录制侧在途映射：（线程, target）复合键 → 待上传区间。 */
    private static final Map<Long, PendingMap> PENDING = new HashMap<>();

    /** 渲染线程上传用直接 staging（仅渲染线程触碰，按需增长）。 */
    private static ByteBuffer staging;

    /** 仿真诊断开关（{@code -Dssoptimizer.debug.buffermap=true}）：统计写映射仿真命中率。 */
    private static final boolean DEBUG =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.debug.buffermap", "false"));
    private static final org.apache.log4j.Logger DEBUG_LOGGER =
            org.apache.log4j.Logger.getLogger(BufferMapEmulator.class);
    private static final long DEBUG_STATS_INTERVAL_NANOS = 15_000_000_000L;
    private static long debugEmulatedMaps;
    private static long debugEmulatedUploads;
    private static long debugFallbacks;
    private static long debugLastStatsNanos;

    private BufferMapEmulator() {
    }

    /** 在途映射（录制侧）。 */
    private static final class PendingMap {
        final int vbo;
        final long offset;
        final int length;

        PendingMap(final int vbo, final long offset, final int length) {
            this.vbo = vbo;
            this.offset = offset;
            this.length = length;
        }
    }

    /** 待上传区间快照（unmap 时产生，随帧命令流入队）。 */
    static final class PendingUpload {
        final int    vbo;
        final int    target;
        final long   offset;
        final byte[] data;

        PendingUpload(final int vbo, final int target, final long offset, final byte[] data) {
            this.vbo = vbo;
            this.target = target;
            this.offset = offset;
            this.data = data;
        }
    }

    /** 录制侧绑定簿记（桥接 glBindBuffer 调用），按调用线程隔离。 */
    static synchronized void onBindBuffer(final int target, final int buffer) {
        BOUND.put(pendingKey(target), buffer);
    }

    /** 录制侧容量簿记（桥接 glBufferData 调用，作用于本线程当前绑定 VBO）。 */
    static synchronized void onBufferData(final int target, final long size) {
        final Integer vbo = BOUND.get(pendingKey(target));
        if (vbo != null && vbo != 0) {
            SIZES.put(vbo, (int) Math.min(size, Integer.MAX_VALUE));
        }
    }

    /** 录制侧删除簿记（桥接 glDeleteBuffers 调用）：镜像随 VBO 一并失效。 */
    static synchronized void onDeleteBuffer(final int buffer) {
        MIRRORS.remove(buffer);
        SIZES.remove(buffer);
    }

    /**
     * 尝试以镜像缓冲仿真写映射。
     *
     * @return 映射镜像（position=0、limit=length），或 null 表示不可仿真（桥接回退阻塞通道）
     */
    static synchronized ByteBuffer tryEmulateMapRange(final int target, final long offset,
                                                      final long length, final int access) {
        // 纯写映射才可仿真：调用方读旧内容（MAP_READ）时镜像语义不成立
        if ((access & GL30.GL_MAP_WRITE_BIT) == 0 || (access & GL30.GL_MAP_READ_BIT) != 0) {
            debugFallback();
            return null;
        }
        if (bindingQueryPname(target) == 0 || offset < 0 || length <= 0 || length > Integer.MAX_VALUE) {
            debugFallback();
            return null;
        }
        final Integer vbo = BOUND.get(pendingKey(target));
        final long pendingKey = pendingKey(target);
        if (vbo == null || vbo == 0 || PENDING.containsKey(pendingKey)) {
            debugFallback();
            return null;
        }
        final int len = (int) length;
        ByteBuffer mirror = MIRRORS.get(vbo);
        if (mirror == null || mirror.capacity() < len) {
            mirror = BufferUtils.createByteBuffer(Math.max(len, mirror == null ? 0 : mirror.capacity() * 2));
            MIRRORS.put(vbo, mirror);
        }
        PENDING.put(pendingKey, new PendingMap(vbo, offset, len));
        if (DEBUG) {
            if (debugEmulatedMaps == 0L) {
                DEBUG_LOGGER.info("[SSOptimizer] 首次仿真写映射：target=0x" + Integer.toHexString(target)
                        + " vbo=" + vbo + " offset=" + offset + " length=" + len
                        + " access=0x" + Integer.toHexString(access));
            }
            debugEmulatedMaps++;
            maybeLogStatsLocked();
        }
        final ByteBuffer view = mirror.duplicate();
        // duplicate() 会将字节序重置为 BIG_ENDIAN（JDK 行为），必须恢复镜像原序：
        // 调用方可能经 asFloatBuffer() 等类型化视图写入，序错会以翻转字节序落盘并上传 GPU
        view.order(mirror.order());
        view.position(0);
        view.limit(len);
        return view;
    }

    private static void debugFallback() {
        if (DEBUG) {
            debugFallbacks++;
            maybeLogStatsLocked();
        }
    }

    /** 周期性输出仿真统计（持有类锁期间调用，间隔门控，常态零开销）。 */
    private static void maybeLogStatsLocked() {
        final long now = System.nanoTime();
        if (debugLastStatsNanos != 0L && now - debugLastStatsNanos < DEBUG_STATS_INTERVAL_NANOS) {
            return;
        }
        debugLastStatsNanos = now;
        if (debugEmulatedMaps + debugFallbacks == 0L) {
            return;
        }
        DEBUG_LOGGER.info("[SSOptimizer] 写映射仿真统计：emulated=" + debugEmulatedMaps
                + " uploads=" + debugEmulatedUploads
                + " fallback=" + debugFallbacks);
    }

    /**
     * unmap 配对：取出<b>本线程</b>在该 target 上的在途映射并把镜像写入区间快照为字节数组。
     *
     * @return 待上传快照，或 null 表示本线程在该 target 无在途仿真映射（桥接回退真实 unmap）
     */
    static synchronized PendingUpload pollEmulatedUnmap(final int target) {
        final PendingMap pending = PENDING.remove(pendingKey(target));
        if (pending == null) {
            return null;
        }
        if (DEBUG) {
            debugEmulatedUploads++;
        }
        final ByteBuffer mirror = MIRRORS.get(pending.vbo);
        if (mirror == null) {
            // 映射期间 VBO 被删除：内容无意义，丢弃即可
            return new PendingUpload(pending.vbo, target, pending.offset, null);
        }
        final byte[] copy = new byte[pending.length];
        final ByteBuffer view = mirror.duplicate();
        view.position(0);
        view.limit(pending.length);
        view.get(copy);
        return new PendingUpload(pending.vbo, target, pending.offset, copy);
    }

    /**
     * 渲染线程上传：快照经 staging 直接缓冲做真实 {@code glBufferSubData}，
     * 前后恢复目标绑定，不干扰录制流中的绑定序列。
     */
    static void enqueueUpload(final PendingUpload upload) {
        if (upload.data == null) {
            return;
        }
        if (github.kasuminova.ssoptimizer.common.render.queue.RtTrace.enabled()) {
            github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                    "BUF_UPLOAD", upload.vbo, upload.offset, upload.data.length,
                    "target=" + upload.target + " "
                            + github.kasuminova.ssoptimizer.common.render.queue.RtTrace.floatStats(upload.data)
                            + " "
                            + github.kasuminova.ssoptimizer.common.render.queue.RtTrace.hexPrefix(upload.data, 32));
        }
        BridgeSupport.enqueue(() -> {
            if (staging == null || staging.capacity() < upload.data.length) {
                staging = BufferUtils.createByteBuffer(
                        Math.max(upload.data.length, staging == null ? 0 : staging.capacity() * 2));
            }
            staging.clear();
            staging.put(upload.data);
            staging.flip();
            final int query = bindingQueryPname(upload.target);
            final int prev = GL11.glGetInteger(query);
            GL15.glBindBuffer(upload.target, upload.vbo);
            GL15.glBufferSubData(upload.target, upload.offset, staging);
            GL15.glBindBuffer(upload.target, prev);
        });
    }

    /** 在途映射复合键：调用线程 id 高 32 位 | target 低 32 位（线程 id 在 JVM 内单调不复用）。 */
    private static long pendingKey(final int target) {
        return Thread.currentThread().getId() << 32 | target & 0xFFFFFFFFL;
    }

    /** 本线程在该 target 上是否有在途仿真映射（{@code glFlushMappedBufferRange} 桥接判定用）。 */
    static synchronized boolean hasPendingMap(final int target) {
        return PENDING.containsKey(pendingKey(target));
    }

    /** target 对应的绑定查询 pname；不认识的 target 返回 0（不可安全仿真）。 */
    private static int bindingQueryPname(final int target) {
        if (target == GL15.GL_ARRAY_BUFFER) {
            return GL15.GL_ARRAY_BUFFER_BINDING;
        }
        if (target == GL15.GL_ELEMENT_ARRAY_BUFFER) {
            return GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING;
        }
        if (target == GL21.GL_PIXEL_PACK_BUFFER) {
            return GL21.GL_PIXEL_PACK_BUFFER_BINDING;
        }
        if (target == GL21.GL_PIXEL_UNPACK_BUFFER) {
            return GL21.GL_PIXEL_UNPACK_BUFFER_BINDING;
        }
        if (target == GL31.GL_TEXTURE_BUFFER) {
            // GL_TEXTURE_BUFFER_BINDING 与 GL_TEXTURE_BUFFER 枚举值相同（0x8C2A），
            // LWJGL2 未在 GL31 提供绑定常量，直接复用 target 值
            return GL31.GL_TEXTURE_BUFFER;
        }
        if (target == GL31.GL_UNIFORM_BUFFER) {
            return GL31.GL_UNIFORM_BUFFER_BINDING;
        }
        if (target == GL43.GL_SHADER_STORAGE_BUFFER) {
            return GL43.GL_SHADER_STORAGE_BUFFER_BINDING;
        }
        return 0;
    }

    /** 测试用：清空全部簿记与镜像，避免用例间静态状态串扰。 */
    static synchronized void reset() {
        BOUND.clear();
        SIZES.clear();
        MIRRORS.clear();
        PENDING.clear();
        staging = null;
        debugEmulatedMaps = 0L;
        debugEmulatedUploads = 0L;
        debugFallbacks = 0L;
        debugLastStatsNanos = 0L;
    }
}
