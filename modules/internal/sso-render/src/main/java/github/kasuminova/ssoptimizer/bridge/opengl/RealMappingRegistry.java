package github.kasuminova.ssoptimizer.bridge.opengl;

import org.apache.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 真实映射（非仿真）的生命周期登记：bridge 的 glMapBufferRange 回退真实阻塞映射时
 * 按（VBO id, 映射 ByteBuffer）登记令牌；unmap / deleteBuffers / bufferData（存储
 * 重建即映射失效）经 bridge 扼流点即时失效对应令牌。
 * <p>
 * 动机：{@link MappedWriteBridgeImpl} 的有序写入命令把「向映射内存落笔」推迟到渲染
 * 线程执行（可能晚至下一帧），期间模组线程若触发池扩容/销毁（BoxUtil
 * {@code GPUMemoryPool._expandBuffer} 会 unmap 旧映射并重建 VBO），命令将写向已
 * 释放的映射地址（SIGSEGV 级崩溃）。令牌在 unmap 的 bridge 扼流点同步失效——
 * 先于真实 glUnmapBuffer 执行，渲染线程侧命令执行时复查令牌即可安全跳过。
 * <p>
 * 与 {@link BufferMapEmulator} 的分工：后者簿记服务于镜像仿真（容量/在途映射），
 * 本类只回答「这个真实映射还活着吗」，两者键同源（{@link BufferMapEmulator} 的
 * 录制侧绑定簿记）。
 */
final class RealMappingRegistry {
    private static final Logger LOGGER = Logger.getLogger(RealMappingRegistry.class);

    /** 映射生命周期令牌：失效即「映射内存已被/即将被释放」，写入方必须停笔。 */
    static final class Token {
        private final int bufferId;
        private final ByteBuffer mapping;
        private final AtomicBoolean valid = new AtomicBoolean(true);

        Token(final int bufferId, final ByteBuffer mapping) {
            this.bufferId = bufferId;
            this.mapping = mapping;
        }

        boolean isValid() {
            return valid.get();
        }

        int bufferId() {
            return bufferId;
        }
    }

    private static final Map<Integer, List<Token>> BY_BUFFER = new HashMap<>();
    private static final Map<ByteBuffer, Token> BY_MAPPING = new IdentityHashMap<>();

    private RealMappingRegistry() {
    }

    /**
     * 登记一个真实映射。
     *
     * @return 生命周期令牌；bufferId 不合法（无绑定簿记）时记警告并返回 null
     */
    static synchronized Token track(final int bufferId, final ByteBuffer mapping) {
        if (bufferId <= 0 || mapping == null) {
            LOGGER.warn("[SSOptimizer] 真实映射登记缺绑定簿记：vbo=" + bufferId
                    + "（该映射的流序写入将因无法校验生命周期被拒绝）");
            return null;
        }
        final Token token = new Token(bufferId, mapping);
        BY_BUFFER.computeIfAbsent(bufferId, k -> new ArrayList<>(1)).add(token);
        BY_MAPPING.put(mapping, token);
        return token;
    }

    /**
     * 查询映射的登记令牌。
     *
     * @return 令牌；映射未经 bridge 真实映射路径创建（或已失效清理）时返回 null
     */
    static synchronized Token tokenFor(final ByteBuffer mapping) {
        return BY_MAPPING.get(mapping);
    }

    /**
     * 使某 VBO 的全部映射令牌失效（unmap/delete/存储重建扼流点调用，
     * 必须先于对应的真实 GL 调用执行）。
     */
    static synchronized void invalidateBuffer(final int bufferId) {
        final List<Token> tokens = BY_BUFFER.remove(bufferId);
        if (tokens == null) {
            return;
        }
        for (final Token token : tokens) {
            token.valid.set(false);
            BY_MAPPING.remove(token.mapping);
        }
    }

    /** 测试用：清空全部登记，避免用例间静态状态串扰。 */
    static synchronized void reset() {
        BY_BUFFER.clear();
        BY_MAPPING.clear();
    }
}
