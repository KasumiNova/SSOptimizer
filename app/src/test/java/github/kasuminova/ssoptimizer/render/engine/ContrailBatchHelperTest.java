package github.kasuminova.ssoptimizer.render.engine;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class ContrailBatchHelperTest {
    private static int invokePrivateInt(String methodName, int argument) throws Exception {
        Method method = ContrailBatchHelper.class.getDeclaredMethod(methodName, int.class);
        method.setAccessible(true);
        return (int) method.invoke(null, argument);
    }

    private static boolean invokePrivateBoolean(String methodName, Object argument) throws Exception {
        Method method = ContrailBatchHelper.class.getDeclaredMethod(methodName, Object.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, argument);
    }

    @Test
    void clampColorComponentKeepsAlphaInByteRange() throws Exception {
        assertEquals(0, invokePrivateInt("clampColorComponent", -12));
        assertEquals(42, invokePrivateInt("clampColorComponent", 42));
        assertEquals(255, invokePrivateInt("clampColorComponent", 999));
    }

    @Test
    void glowBlendModeUsesEnumNameMatching() throws Exception {
        assertTrue(invokePrivateBoolean("isGlowBlendMode", FakeBlendMode.GLOW));
        assertFalse(invokePrivateBoolean("isGlowBlendMode", FakeBlendMode.NORMAL));
        assertFalse(invokePrivateBoolean("isGlowBlendMode", "GLOW"));
    }

    private enum FakeBlendMode {
        GLOW,
        NORMAL
    }
}