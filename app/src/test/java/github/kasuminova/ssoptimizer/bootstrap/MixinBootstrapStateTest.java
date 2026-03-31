package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MixinBootstrapStateTest {
    @Test
    void mixinBootstrapStateDefaultsToDisabled() {
        assertFalse(SSOptimizerAgent.isMixinAvailable());
    }
}
