package github.kasuminova.ssoptimizer.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 映射双向查询契约测试。
 * <p>
 * 该测试约束类、字段和方法都必须可以通过 obfuscated / named 两个命名空间互查，
 * 避免后续实现只支持单向转换或依赖临时字符串替换。
 */
class MappingLookupTest {

    private final MappingLookup lookup = new MappingLookup(TinyV2MappingRepository.loadDefault());

    @Test
    void classFieldAndMethodCanBeResolvedBidirectionally() {
        MappingEntry classEntry = lookup.requireClassByObfuscatedName("com/fs/graphics/TextureLoader");
        MappingEntry fieldEntry = lookup.requireFieldByNamedName("com/fs/graphics/TextureLoader", "cacheSize");
        MappingEntry methodEntry = lookup.requireMethodByObfuscatedName("com/fs/graphics/TextureLoader", "b", "(I)V");

        assertEquals("com/fs/graphics/TextureLoader", classEntry.namedName());
        assertEquals("cacheSize", fieldEntry.namedName());
        assertEquals("reloadCache", methodEntry.namedName());
        assertEquals("(I)V", methodEntry.descriptor());
    }

    @Test
    void methodCanBeResolvedByNamedDescriptorAfterClassRemap() {
        MappingEntry methodEntry = lookup.requireMethodByNamedName(
                "com/fs/graphics/TextureLoader",
                "convertPixels",
                "(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/TextureObject;)Ljava/nio/ByteBuffer;");

        assertEquals("super", methodEntry.obfuscatedName());
        assertEquals("(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/Object;)Ljava/nio/ByteBuffer;", methodEntry.descriptor());
    }

    @Test
    void renamedClassMethodCanBeResolvedByObfuscatedDescriptor() {
        MappingEntry methodEntry = lookup.requireMethodByObfuscatedName(
                "com/fs/graphics/super/D",
                "Ò00000",
                "(Ljava/lang/String;)Lcom/fs/graphics/super/return;");

        assertEquals("getFont", methodEntry.namedName());
        assertEquals("(Ljava/lang/String;)Lcom/fs/graphics/font/BitmapFont;", methodEntry.descriptor());
    }

    @Test
    void privateParallelPreloaderByteLoaderCanBeResolvedByNamedName() {
        MappingEntry methodEntry = lookup.requireMethodByNamedName(
                "com/fs/graphics/ParallelImagePreloader",
                "loadBytes",
                "(Ljava/lang/String;)[B");

        assertEquals("Ô00000", methodEntry.obfuscatedName());
        assertEquals("(Ljava/lang/String;)[B", methodEntry.descriptor());
    }

    @Test
    void textureObjectImageSetterMappingsMatchRuntimeTextureLoaderSemantics() {
        MappingEntry widthSetter = lookup.requireMethodByNamedName(
                "com/fs/graphics/TextureObject",
                "setImageWidth",
                "(I)V");
        MappingEntry heightSetter = lookup.requireMethodByNamedName(
                "com/fs/graphics/TextureObject",
                "setImageHeight",
                "(I)V");

        assertEquals("Ò00000", widthSetter.obfuscatedName());
        assertEquals("o00000", heightSetter.obfuscatedName());
    }

    @Test
        void textureManagerLazyModeAndMipmapMappingsCanBeResolvedByNamedName() {
                MappingEntry classEntry = lookup.requireClassByNamedName("com/fs/graphics/TextureManager");
                MappingEntry methodEntry = lookup.requireMethodByNamedName(
                                "com/fs/graphics/TextureManager",
                                "isLazyLoadingEnabled",
                                "()Z");
                MappingEntry fieldEntry = lookup.requireFieldByNamedName(
                                "com/fs/graphics/TextureLoader",
                                "specialMipmapSet");

                assertEquals("com/fs/graphics/oOoO", classEntry.obfuscatedName());
                assertEquals("class", methodEntry.obfuscatedName());
                assertEquals("null", fieldEntry.obfuscatedName());
        }

        @Test
    void soundManagerPathLoaderMappingCanBeResolvedByNamedName() {
        MappingEntry classEntry = lookup.requireClassByNamedName("sound/SoundManager");
        MappingEntry methodEntry = lookup.requireMethodByNamedName(
                "sound/SoundManager",
                "loadOAccentFamily",
                "(Ljava/lang/String;)Lsound/O0OO;");

        assertEquals("sound/Object", classEntry.obfuscatedName());
        assertEquals("Ò00000", methodEntry.obfuscatedName());
    }

        @Test
        void soundManagerStreamLoaderMappingCanBeResolvedByNamedName() {
                MappingEntry objectFamilyStream = lookup.requireMethodByNamedName(
                                "sound/SoundManager",
                                "loadObjectFamilyFromStream",
                                "(Ljava/lang/String;Ljava/io/InputStream;)Lsound/O0OO;");
                MappingEntry oAccentFamilyStream = lookup.requireMethodByNamedName(
                                "sound/SoundManager",
                                "loadOAccentFamilyFromStream",
                                "(Ljava/lang/String;Ljava/io/InputStream;)Lsound/O0OO;");

                assertEquals("Ò00000", objectFamilyStream.obfuscatedName());
                assertEquals("Object", oAccentFamilyStream.obfuscatedName());
        }

    @Test
    void saveProgressMappingsCanBeResolvedByNamedName() {
        MappingEntry dialogClass = lookup.requireClassByNamedName("com/fs/starfarer/campaign/save/CampaignSaveProgressDialog");
        MappingEntry reportProgress = lookup.requireMethodByNamedName(
                "com/fs/starfarer/campaign/save/CampaignSaveProgressDialog",
                "reportProgress",
                "(Ljava/lang/String;F)V");
        MappingEntry streamField = lookup.requireFieldByNamedName(
                "com/fs/starfarer/util/SaveProgressOutputStream",
                "writtenBytes");

        assertEquals("com/fs/starfarer/campaign/save/B", dialogClass.obfuscatedName());
        assertEquals("o00000", reportProgress.obfuscatedName());
        assertEquals("String", streamField.obfuscatedName());
    }

    @Test
    void terrainTileClassMappingsCanBeResolvedByNamedName() {
        MappingEntry baseTerrain = lookup.requireClassByNamedName("com/fs/starfarer/api/impl/campaign/terrain/BaseTiledTerrain");
        MappingEntry automaton = lookup.requireClassByNamedName("com/fs/starfarer/api/impl/campaign/terrain/HyperspaceAutomaton");

        assertEquals("com/fs/starfarer/api/impl/campaign/terrain/BaseTiledTerrain", baseTerrain.obfuscatedName());
        assertEquals("com/fs/starfarer/api/impl/campaign/terrain/HyperspaceAutomaton", automaton.obfuscatedName());
    }

    @Test
    void renderAndSettingsHelperMappingsRemainQueryable() {
        MappingEntry beginOverlay = lookup.requireMethodByNamedName(
                "com/fs/graphics/util/RenderStateUtils",
                "beginScreenOverlay",
                "(FFFFF)V");
        MappingEntry endOverlay = lookup.requireMethodByNamedName(
                "com/fs/graphics/util/RenderStateUtils",
                "endScreenOverlay",
                "()V");
        MappingEntry getBoolean = lookup.requireMethodByNamedName(
                "com/fs/starfarer/settings/StarfarerSettings",
                "getBoolean",
                "(Ljava/lang/String;)Z");

        assertEquals("o00000", beginOverlay.obfuscatedName());
        assertEquals("class", endOverlay.obfuscatedName());
        assertEquals("Õ00000", getBoolean.obfuscatedName());
    }

        @Test
        void texturedStripRendererAndEngineGlowMappingsRemainQueryable() {
                MappingEntry renderTexturedStrip = lookup.requireMethodByNamedName(
                                "com/fs/starfarer/renderers/TexturedStripRenderer",
                                "renderTexturedStrip",
                                "(Lcom/fs/graphics/TextureObject;FFFFFFLjava/awt/Color;FFFZ)V");
                MappingEntry primaryGlowType = lookup.requireFieldByNamedName(
                                "com/fs/starfarer/combat/entities/EngineGlowType",
                                "PRIMARY");

                assertEquals("o00000", renderTexturedStrip.obfuscatedName());
                assertEquals("Object", primaryGlowType.obfuscatedName());
        }

    @Test
    void missingMethodMappingReportsReadableError() {
        MappingLookup lookup = new MappingLookup(TinyV2MappingRepository.loadDefault());

        MappingLookupException exception = assertThrows(MappingLookupException.class,
                () -> lookup.requireMethodByNamedName("com/fs/graphics/TextureLoader", "missingMethod", "()V"));
        assertEquals("未找到方法映射: com/fs/graphics/TextureLoader#missingMethod()V", exception.getMessage());
    }
}
