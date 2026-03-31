package github.kasuminova.ssoptimizer.common.loading;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GLContext;

/**
 * Determines whether texture uploads can keep their original dimensions instead
 * of rounding them up to the nearest power-of-two.
 */
public final class TextureDimensionSupport {
    static final String DISABLE_PROPERTY = "ssoptimizer.disable.npot";
    static final String FORCE_PROPERTY   = "ssoptimizer.force.npot";

    private static final int UNKNOWN  = 0;
    private static final int ENABLED  = 1;
    private static final int DISABLED = 2;

    private static volatile int cachedSupport = UNKNOWN;

    private TextureDimensionSupport() {
    }

    public static int textureDimension(final int value) {
        if (value <= 0) {
            return 0;
        }
        if (useNpotTextures()) {
            return value;
        }
        return nextPowerOfTwo(value);
    }

    static boolean useNpotTextures() {
        if (Boolean.getBoolean(DISABLE_PROPERTY)) {
            return false;
        }
        if (Boolean.getBoolean(FORCE_PROPERTY)) {
            return true;
        }

        final int cached = cachedSupport;
        if (cached != UNKNOWN) {
            return cached == ENABLED;
        }

        try {
            final ContextCapabilities capabilities = GLContext.getCapabilities();
            final boolean supported = capabilities.OpenGL20 || capabilities.GL_ARB_texture_non_power_of_two;
            cachedSupport = supported ? ENABLED : DISABLED;
            return supported;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void resetCachedSupport() {
        cachedSupport = UNKNOWN;
    }

    private static int nextPowerOfTwo(final int value) {
        int result = 2;
        while (result < value) {
            result *= 2;
        }
        return result;
    }
}