package github.kasuminova.ssoptimizer.bridge.opengl;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 纯写 buffer 映射的录制侧镜像。unmap 时快照写入区间并入队上传；含读取、持久或
 * 相干映射的调用返回 null，由桥接层回退真实映射。
 * <p>
 * 绑定与在途映射按（线程, target）隔离，避免并发生产者的 unmap 与绑定错配。
 */
final class BufferMapEmulator {
    private static final Map<Long, Integer> BOUND = new HashMap<>();
    private static final Map<Integer, Integer> SIZES = new HashMap<>();
    private static final Map<Integer, ByteBuffer> MIRRORS = new HashMap<>();
    private static final Map<Long, PendingMap> PENDING = new HashMap<>();

    private static ByteBuffer staging;

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

    static synchronized void onBindBuffer(final int target, final int buffer) {
        BOUND.put(pendingKey(target), buffer);
    }

    static synchronized void onBufferData(final int target, final long size) {
        final Integer vbo = BOUND.get(pendingKey(target));
        if (vbo != null && vbo != 0) {
            SIZES.put(vbo, (int) Math.min(size, Integer.MAX_VALUE));
            // bufferData 会使既有映射失效
            RealMappingRegistry.invalidateBuffer(vbo);
        }
    }

    static synchronized void onDeleteBuffer(final int buffer) {
        MIRRORS.remove(buffer);
        SIZES.remove(buffer);
        RealMappingRegistry.invalidateBuffer(buffer);
    }

    static synchronized int boundBufferForCurrentThread(final int target) {
        final Integer vbo = BOUND.get(pendingKey(target));
        return vbo == null ? 0 : vbo;
    }

    /**
     * @return 映射镜像（position=0、limit=length），或 null 表示调用方必须回退真实映射
     */
    static synchronized ByteBuffer tryEmulateMapRange(final int target, final long offset,
                                                      final long length, final int access) {
        // 纯写映射才可仿真：调用方读旧内容（MAP_READ）时镜像语义不成立
        if ((access & GL30.GL_MAP_WRITE_BIT) == 0 || (access & GL30.GL_MAP_READ_BIT) != 0) {
            debugFallback();
            return null;
        }
        // 持久/相干映射常驻不 unmap，数据可见性依赖真实映射内存，镜像快照模型不成立
        if ((access & (GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT)) != 0) {
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
        // duplicate() 会重置字节序，类型化视图写入前必须恢复镜像原序
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
     * @return 待上传快照，或 null 表示本线程在该 target 无在途仿真映射
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

    /** 渲染线程上传前后恢复原 target 绑定。 */
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

    private static long pendingKey(final int target) {
        return Thread.currentThread().getId() << 32 | target & 0xFFFFFFFFL;
    }

    static synchronized boolean hasPendingMap(final int target) {
        return PENDING.containsKey(pendingKey(target));
    }

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
            // LWJGL2 未提供 GL_TEXTURE_BUFFER_BINDING，复用数值相同的 target 枚举
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
