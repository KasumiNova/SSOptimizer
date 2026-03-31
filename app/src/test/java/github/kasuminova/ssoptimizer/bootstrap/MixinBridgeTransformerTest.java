package github.kasuminova.ssoptimizer.bootstrap;

import org.codehaus.janino.JavaSourceClassLoader;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinBridgeTransformerTest {
    @Test
    void skipsAgentAndPlatformClasses() {
        assertTrue(MixinBridgeTransformer.shouldSkipClass("java/lang/String"));
        assertTrue(MixinBridgeTransformer.shouldSkipClass("org/spongepowered/asm/mixin/MixinEnvironment"));
        assertTrue(MixinBridgeTransformer.shouldSkipClass("github/kasuminova/ssoptimizer/mixin/render/GEngineRenderMixin"));
        assertTrue(MixinBridgeTransformer.shouldSkipClass("thirdparty/mod/PlainModEntry"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("com/fs/starfarer/combat/entities/G"));
    }

    @Test
    void skipsJaninoLoadedClasses() {
        ClassLoader janinoLoader = new JavaSourceClassLoader(getClass().getClassLoader(), new File[0], null);
        assertTrue(MixinBridgeTransformer.shouldSkipClass(janinoLoader, "com/fs/starfarer/combat/entities/G"));
    }
}