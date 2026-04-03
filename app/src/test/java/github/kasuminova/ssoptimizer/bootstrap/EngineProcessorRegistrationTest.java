package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EngineProcessorRegistrationTest {
    @Test
    void registersOnlyEngineProcessorsForRuntimeRepairPhase() throws Exception {
        HybridWeaverTransformer transformer = new HybridWeaverTransformer();
        Method register = SSOptimizerAgent.class.getDeclaredMethod("registerEngineProcessors", HybridWeaverTransformer.class);
        register.setAccessible(true);
        register.invoke(null, transformer);

        assertEquals(20, transformer.getProcessorCount());
        assertNull(transformer.transform(null, "org/lwjgl/opengl/GL13", null, null, new byte[]{1}));
    }
}
