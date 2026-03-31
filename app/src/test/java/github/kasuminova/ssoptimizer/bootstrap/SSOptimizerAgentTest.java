package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class SSOptimizerAgentTest {
    @Test
    void premainMethodHasCorrectSignature() throws Exception {
        Method premain = SSOptimizerAgent.class.getMethod("premain", String.class, Instrumentation.class);
        assertTrue(Modifier.isPublic(premain.getModifiers()));
        assertTrue(Modifier.isStatic(premain.getModifiers()));
        assertSame(void.class, premain.getReturnType());
    }

    @Test
    void premainStateIsEmptyBeforeAgentLoad() {
        assertNull(SSOptimizerAgent.getInstrumentation());
        assertNull(SSOptimizerAgent.getWeaverTransformer());
    }
}
