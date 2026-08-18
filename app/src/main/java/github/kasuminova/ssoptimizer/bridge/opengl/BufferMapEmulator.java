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
 * 非仿真情形（含 MAP_READ、同 target 重入映射、未跟踪的 target、未绑定 VBO）返回
 * null，由桥接回退真实阻塞映射——语义正确性优先于零阻塞。
 */
final class BufferMapEmulator {
    /** 录制侧簿记：buffer target → 当前绑定 VBO 名。 */
    private static final Map<Integer, Integer> BOUND = new HashMap<>();
    /** 录制侧簿记：VBO 名 → 最近 glBufferData 容量（字节）。 */
    private static final Map<Integer, Integer> SIZES = new HashMap<>();
    /** 录制侧镜像：VBO 名 → 直接缓冲（按需增长复用）。 */
    private static final Map<Integer, ByteBuffer> MIRRORS = new HashMap<>();
    /** 录制侧在途映射：target → 待上传区间。 */
    private static final Map<Integer, PendingMap> PENDING = new HashMap<>();

    /** 渲染线程上传用直接 staging（仅渲染线程触碰，按需增长）。 */
    private static ByteBuffer staging;

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

    /** 录制侧绑定簿记（桥接 glBindBuffer 调用）。 */
    static synchronized void onBindBuffer(final int target, final int buffer) {
        BOUND.put(target, buffer);
    }

    /** 录制侧容量簿记（桥接 glBufferData 调用，作用于当前绑定 VBO）。 */
    static synchronized void onBufferData(final int target, final long size) {
        final Integer vbo = BOUND.get(target);
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
            return null;
        }
        if (bindingQueryPname(target) == 0 || offset < 0 || length <= 0 || length > Integer.MAX_VALUE) {
            return null;
        }
        final Integer vbo = BOUND.get(target);
        if (vbo == null || vbo == 0 || PENDING.containsKey(target)) {
            return null;
        }
        final int len = (int) length;
        ByteBuffer mirror = MIRRORS.get(vbo);
        if (mirror == null || mirror.capacity() < len) {
            mirror = BufferUtils.createByteBuffer(Math.max(len, mirror == null ? 0 : mirror.capacity() * 2));
            MIRRORS.put(vbo, mirror);
        }
        PENDING.put(target, new PendingMap(vbo, offset, len));
        final ByteBuffer view = mirror.duplicate();
        view.position(0);
        view.limit(len);
        return view;
    }

    /**
     * unmap 配对：取出在途映射并把镜像写入区间快照为字节数组。
     *
     * @return 待上传快照，或 null 表示该 target 无在途仿真映射（桥接回退真实 unmap）
     */
    static synchronized PendingUpload pollEmulatedUnmap(final int target) {
        final PendingMap pending = PENDING.remove(target);
        if (pending == null) {
            return null;
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
    }
}
