package github.kasuminova.ssoptimizer.common.render.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRenderHelperTest {
    @Test
    void enablesExactAlphaNativePathForTranslucentEnginePasses() {
        assertTrue(EngineRenderHelper.requiresExactAlphaNativePath(0.75f));
        assertFalse(EngineRenderHelper.requiresExactAlphaNativePath(1.0f));
    }
}