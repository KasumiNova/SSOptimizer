package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EngineProcessorRegistrationTest {
    @Test
    void skipsEngineRenderProcessorsByDefault() throws Exception {
        System.clearProperty("ssoptimizer.render.engine.enable");

        HybridWeaverTransformer transformer = new HybridWeaverTransformer();
        Method register = SSOptimizerAgent.class.getDeclaredMethod("registerEngineProcessors", HybridWeaverTransformer.class);
        register.setAccessible(true);
        register.invoke(null, transformer);

        assertEquals(14, transformer.getProcessorCount());
        assertNull(transformer.transform(null, "com/fs/graphics/Sprite", null, null, new byte[]{1}));
        assertNull(transformer.transform(null, "org/lwjgl/opengl/GL13", null, null, new byte[]{1}));
    }

    @Test
    void registersEngineRenderProcessorsWhenExplicitlyEnabled() throws Exception {
        System.setProperty("ssoptimizer.render.engine.enable", "true");
        try {
            HybridWeaverTransformer transformer = new HybridWeaverTransformer();
            Method register = SSOptimizerAgent.class.getDeclaredMethod("registerEngineProcessors", HybridWeaverTransformer.class);
            register.setAccessible(true);
            register.invoke(null, transformer);

            assertEquals(21, transformer.getProcessorCount());
        } finally {
            System.clearProperty("ssoptimizer.render.engine.enable");
        }
    }
}
