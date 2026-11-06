package github.kasuminova.ssoptimizer.common.render.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlCapabilityTest {
    @Test
    void resolveFollowsDowngradeChain() {
        assertEquals(GlCapability.Mode.VBO_BATCH,
                GlCapability.resolve(GlCapability.Mode.VBO_BATCH, true));
        assertEquals(GlCapability.Mode.IMMEDIATE,
                GlCapability.resolve(GlCapability.Mode.VBO_BATCH, false));
        assertEquals(GlCapability.Mode.IMMEDIATE,
                GlCapability.resolve(GlCapability.Mode.IMMEDIATE, true));
    }
}
