package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RealMappingRegistry} 的真实映射生命周期登记验证：
 * 登记/查询/失效/清空四组静态操作的真实簿记行为。
 */
class RealMappingRegistryTest {

    @AfterEach
    void tearDown() {
        RealMappingRegistry.reset();
    }

    @Test
    void trackRegistersLookupableValidToken() {
        ByteBuffer mapping = ByteBuffer.allocateDirect(64);
        RealMappingRegistry.Token token = RealMappingRegistry.track(7, mapping);
        assertNotNull(token, "合法登记必须返回令牌");
        assertSame(token, RealMappingRegistry.tokenFor(mapping), "登记后必须能按映射查回令牌");
        assertTrue(token.isValid(), "新登记的令牌必须有效");
        assertEquals(7, token.bufferId(), "令牌必须携带登记时的 VBO id");
    }

    @Test
    void trackRejectsNonPositiveBufferId() {
        ByteBuffer mapping = ByteBuffer.allocateDirect(16);
        assertNull(RealMappingRegistry.track(0, mapping), "bufferId=0 不合法");
        assertNull(RealMappingRegistry.track(-3, mapping), "bufferId<0 不合法");
        assertNull(RealMappingRegistry.tokenFor(mapping), "被拒绝的登记不得留下可查询令牌");
    }

    @Test
    void invalidateBufferDropsAllTokensOfThatBuffer() {
        ByteBuffer mappingA = ByteBuffer.allocateDirect(16);
        ByteBuffer mappingB = ByteBuffer.allocateDirect(16);
        ByteBuffer mappingOther = ByteBuffer.allocateDirect(16);
        RealMappingRegistry.Token tokenA = RealMappingRegistry.track(7, mappingA);
        RealMappingRegistry.Token tokenB = RealMappingRegistry.track(7, mappingB);
        RealMappingRegistry.Token tokenOther = RealMappingRegistry.track(8, mappingOther);
        assertNotNull(tokenA);
        assertNotNull(tokenB);
        assertNotNull(tokenOther);

        RealMappingRegistry.invalidateBuffer(7);
        assertNull(RealMappingRegistry.tokenFor(mappingA), "失效后映射查不到令牌");
        assertNull(RealMappingRegistry.tokenFor(mappingB), "同一 VBO 的全部映射令牌一并失效");
        assertFalse(tokenA.isValid(), "已失效令牌必须向持有方报告无效");
        assertFalse(tokenB.isValid());
        assertSame(tokenOther, RealMappingRegistry.tokenFor(mappingOther), "其他 VBO 的登记不受影响");
        assertTrue(tokenOther.isValid());

        assertDoesNotThrow(() -> RealMappingRegistry.invalidateBuffer(7), "重复失效同一 VBO 幂等");
        assertDoesNotThrow(() -> RealMappingRegistry.invalidateBuffer(999), "失效未登记的 VBO 不抛异常");
    }

    @Test
    void resetClearsAllRegistrations() {
        ByteBuffer mappingA = ByteBuffer.allocateDirect(16);
        ByteBuffer mappingB = ByteBuffer.allocateDirect(16);
        assertNotNull(RealMappingRegistry.track(7, mappingA));
        assertNotNull(RealMappingRegistry.track(8, mappingB));

        RealMappingRegistry.reset();
        assertNull(RealMappingRegistry.tokenFor(mappingA), "reset 后全部登记清空");
        assertNull(RealMappingRegistry.tokenFor(mappingB));

        // 清空后可正常重新登记（验证不是一次性卡死）
        RealMappingRegistry.Token token = RealMappingRegistry.track(7, mappingA);
        assertNotNull(token);
        assertSame(token, RealMappingRegistry.tokenFor(mappingA));
    }
}
