package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextureDimensionSupportTest {
    @AfterEach
    void tearDown() {
        System.clearProperty(TextureDimensionSupport.DISABLE_PROPERTY);
        System.clearProperty(TextureDimensionSupport.FORCE_PROPERTY);
        TextureDimensionSupport.resetCachedSupport();
    }

    @Test
    void defaultsToPowerOfTwoWithoutContext() {
        assertFalse(TextureDimensionSupport.useNpotTextures());
        assertEquals(1024, TextureDimensionSupport.textureDimension(513));
    }

    @Test
    void forceFlagEnablesNpotDimensions() {
        System.setProperty(TextureDimensionSupport.FORCE_PROPERTY, "true");

        assertTrue(TextureDimensionSupport.useNpotTextures());
        assertEquals(513, TextureDimensionSupport.textureDimension(513));
    }

    @Test
    void disableFlagOverridesForcedOrDetectedSupport() {
        System.setProperty(TextureDimensionSupport.FORCE_PROPERTY, "true");
        System.setProperty(TextureDimensionSupport.DISABLE_PROPERTY, "true");

        assertFalse(TextureDimensionSupport.useNpotTextures());
        assertEquals(1024, TextureDimensionSupport.textureDimension(513));
    }
}