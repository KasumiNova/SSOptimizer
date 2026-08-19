package github.kasuminova.ssoptimizer.common.render.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** GL 能力模式解析与降级链映射测试（纯逻辑，不触碰 GL 上下文）。 */
class GlCapabilityTest {

    @Test
    void parseConfiguredModeMapsKnownValues() {
        assertEquals(GlCapability.Mode.VBO_BATCH, GlCapability.parseConfiguredMode("vbo"));
        assertEquals(GlCapability.Mode.VBO_BATCH, GlCapability.parseConfiguredMode("VBO"));
        assertEquals(GlCapability.Mode.IMMEDIATE, GlCapability.parseConfiguredMode("immediate"));
        assertNull(GlCapability.parseConfiguredMode(null));
        assertNull(GlCapability.parseConfiguredMode(""));
        assertNull(GlCapability.parseConfiguredMode("instanced"), "已移除的模式按未知值处理");
        assertNull(GlCapability.parseConfiguredMode("gpu-magic"), "未知值返回 null 由调用方落默认");
    }

    @Test
    void resolveFollowsDowngradeChain() {
        assertEquals(GlCapability.Mode.VBO_BATCH,
                GlCapability.resolve(GlCapability.Mode.VBO_BATCH, true));
        assertEquals(GlCapability.Mode.IMMEDIATE,
                GlCapability.resolve(GlCapability.Mode.VBO_BATCH, false),
                "无 GL15 时 vbo 降级到 immediate");
        assertEquals(GlCapability.Mode.IMMEDIATE,
                GlCapability.resolve(GlCapability.Mode.IMMEDIATE, true),
                "immediate 为最终兜底，不再降级");
    }
}
