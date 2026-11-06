package github.kasuminova.ssoptimizer.bridge.opengl;

import org.apache.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 在 unmap、删除或存储重建前同步失效映射令牌，防止延迟写入访问已释放内存。 */
final class RealMappingRegistry {
    private static final Logger LOGGER = Logger.getLogger(RealMappingRegistry.class);

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

    static synchronized Token tokenFor(final ByteBuffer mapping) {
        return BY_MAPPING.get(mapping);
    }

    /** 必须先于对应的真实 GL 调用执行。 */
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

    static synchronized void reset() {
        BY_BUFFER.clear();
        BY_MAPPING.clear();
    }
}
