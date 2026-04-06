package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;
import org.codehaus.janino.JavaSourceClassLoader;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinBridgeTransformerTest {
    private static final TinyV2MappingRepository REPOSITORY = TinyV2MappingRepository.loadDefault();

    @Test
    void skipsAgentAndPlatformClasses() {
        String engineRuntimeClass = REPOSITORY.requireClassByNamedName("com/fs/starfarer/combat/entities/Engine").obfuscatedName();
        String soundRuntimeClass = REPOSITORY.requireClassByNamedName("sound/SoundManager").obfuscatedName();

        assertTrue(MixinBridgeTransformer.shouldSkipClass("java/lang/String"));
        assertTrue(MixinBridgeTransformer.shouldSkipClass("org/spongepowered/asm/mixin/MixinEnvironment"));
        assertTrue(MixinBridgeTransformer.shouldSkipClass("github/kasuminova/ssoptimizer/mixin/render/EngineRenderMixin"));
        assertTrue(MixinBridgeTransformer.shouldSkipClass("thirdparty/mod/PlainModEntry"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass(engineRuntimeClass));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("com/sun/xml/txw2/output/DelegatingXMLStreamWriter"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("com/sun/xml/txw2/output/IndentingXMLStreamWriter"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("com/thoughtworks/xstream/core/DefaultConverterLookup"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("com/thoughtworks/xstream/core/ReferenceByIdMarshaller"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("com/thoughtworks/xstream/mapper/FieldAliasingMapper"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("com/thoughtworks/xstream/converters/reflection/FieldDictionary"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("org/codehaus/janino/JavaSourceClassLoader"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("com/thoughtworks/xstream/core/util/Fields"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass("com/thoughtworks/xstream/io/path/PathTracker"));
        assertFalse(MixinBridgeTransformer.shouldSkipClass(soundRuntimeClass));
    }

    @Test
    void skipsJaninoLoadedClasses() {
        ClassLoader janinoLoader = new JavaSourceClassLoader(getClass().getClassLoader(), new File[0], null);
        String engineRuntimeClass = REPOSITORY.requireClassByNamedName("com/fs/starfarer/combat/entities/Engine").obfuscatedName();
        assertTrue(MixinBridgeTransformer.shouldSkipClass(janinoLoader, engineRuntimeClass));
    }
}