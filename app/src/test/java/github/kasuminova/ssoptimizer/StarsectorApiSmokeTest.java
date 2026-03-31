package github.kasuminova.ssoptimizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class StarsectorApiSmokeTest {
    @Test
    void canResolveApiClass() throws Exception {
        Class<?> cls = Class.forName("com.fs.starfarer.api.Global");
        assertNotNull(cls);
    }
}