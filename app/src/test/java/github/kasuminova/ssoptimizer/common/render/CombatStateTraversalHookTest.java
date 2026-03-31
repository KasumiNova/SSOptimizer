package github.kasuminova.ssoptimizer.common.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatStateTraversalHookTest {
    @Test
    void debugModeKeepsFinishCallsEnabled() {
        assertTrue(CombatStateTraversalHook.shouldCallFinish(true));
    }

    @Test
    void normalModeSkipsFinishCalls() {
        assertFalse(CombatStateTraversalHook.shouldCallFinish(false));
    }
}
