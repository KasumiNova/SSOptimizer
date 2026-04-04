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
        assertTrue(MixinBridgeTransformer.shouldSkipClass("github/kasuminova/ssoptimizer/mixin/render/EngineRenderMixin"));
        assertTrue(MixinBridgeTransformer.shouldSkipClass("thirdparty/mod/PlainModEntry"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("com/fs/starfarer/combat/entities/G"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("com/thoughtworks/xstream/converters/reflection/FieldDictionary"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("org/codehaus/janino/JavaSourceClassLoader"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("com/thoughtworks/xstream/core/util/Fields"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("com/thoughtworks/xstream/io/path/PathTracker"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("sound/Object"));
    }

    @Test
    void skipsJaninoLoadedClasses() {
        ClassLoader janinoLoader = new JavaSourceClassLoader(getClass().getClassLoader(), new File[0], null);
        assertTrue(MixinBridgeTransformer.shouldSkipClass(janinoLoader, "com/fs/starfarer/combat/entities/G"));
    }
}