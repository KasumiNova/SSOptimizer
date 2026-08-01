package github.kasuminova.ssoptimizer.mapping;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        MappingEntry fieldEntry = lookup.requireFieldByNamedName("com/fs/graphics/TextureLoader", "textureCache");
        MappingEntry methodByNamed = lookup.requireMethodByNamedName("com/fs/graphics/TextureLoader", "textureDimension", "(I)I");
        MappingEntry methodEntry = lookup.requireMethodByObfuscatedName(
                "com/fs/graphics/TextureLoader", methodByNamed.obfuscatedName(), "(I)I");

        assertEquals("com/fs/graphics/TextureLoader", classEntry.namedName());
        assertEquals("textureCache", fieldEntry.namedName());
        assertEquals("textureDimension", methodEntry.namedName());
        assertEquals("(I)I", methodEntry.descriptor());
    }

    @Test
    void methodCanBeResolvedByNamedDescriptorAfterClassRemap() {
        MappingEntry methodEntry = lookup.requireMethodByNamedName(
                "com/fs/graphics/TextureLoader",
                "convertPixels",
                "(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/TextureObject;)Ljava/nio/ByteBuffer;");

        assertEquals("com/fs/graphics/TextureLoader", methodEntry.ownerNamedName());
        assertEquals("(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/Object;)Ljava/nio/ByteBuffer;", methodEntry.descriptor());
        assertFalse(methodEntry.obfuscatedName().isBlank());
    }

    @Test
    void renamedClassMethodCanBeResolvedByObfuscatedDescriptor() {
        MappingEntry namedMethodEntry = lookup.requireMethodByNamedName(
                "com/fs/graphics/font/BitmapFontManager",
                "getFont",
                "(Ljava/lang/String;)Lcom/fs/graphics/font/BitmapFont;");
        MappingEntry bitmapFontClassEntry = lookup.requireClassByNamedName("com/fs/graphics/font/BitmapFont");
        MappingEntry methodEntry = lookup.requireMethodByObfuscatedName(
                namedMethodEntry.ownerObfuscatedName(),
                namedMethodEntry.obfuscatedName(),
                "(Ljava/lang/String;)L" + bitmapFontClassEntry.obfuscatedName() + ";");

        assertEquals("getFont", methodEntry.namedName());
        assertEquals("com/fs/graphics/font/BitmapFontManager", methodEntry.ownerNamedName());
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
                assertEquals("()Z", methodEntry.descriptor());
                assertEquals("Ljava/util/Set;", fieldEntry.descriptor());
                assertFalse(methodEntry.obfuscatedName().isBlank());
                assertFalse(fieldEntry.obfuscatedName().isBlank());
    }

    @Test
    void soundManagerPathLoaderMappingCanBeResolvedByNamedName() {
        MappingEntry classEntry = lookup.requireClassByNamedName("sound/SoundManager");
        MappingEntry methodEntry = lookup.requireMethodByNamedName(
                "sound/SoundManager",
                "loadOAccentFamily",
                "(Ljava/lang/String;)Lsound/Audio;");

                assertEquals("sound/SoundManager", classEntry.namedName());
                assertEquals("(Ljava/lang/String;)Lsound/O0OO;", methodEntry.descriptor());
                assertFalse(classEntry.obfuscatedName().isBlank());
                assertFalse(methodEntry.obfuscatedName().isBlank());
    }

        @Test
        void soundManagerStreamLoaderMappingCanBeResolvedByNamedName() {
                MappingEntry objectFamilyStream = lookup.requireMethodByNamedName(
                                "sound/SoundManager",
                                "loadObjectFamilyFromStream",
                                "(Ljava/lang/String;Ljava/io/InputStream;)Lsound/Audio;");
                MappingEntry oAccentFamilyStream = lookup.requireMethodByNamedName(
                                "sound/SoundManager",
                                "loadOAccentFamilyFromStream",
                                "(Ljava/lang/String;Ljava/io/InputStream;)Lsound/Audio;");

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
    void commodityAndMarketMappingsRemainQueryable() {
        MappingEntry commodityClass = lookup.requireClassByNamedName("com/fs/starfarer/campaign/econ/CommodityOnMarket");
        MappingEntry reapplyEventMod = lookup.requireMethodByNamedName(
                "com/fs/starfarer/campaign/econ/CommodityOnMarket",
                "reapplyEventMod",
                "()V");
        MappingEntry getAvailableStat = lookup.requireMethodByNamedName(
                "com/fs/starfarer/campaign/econ/CommodityOnMarket",
                "getAvailableStat",
                "()Lcom/fs/starfarer/api/combat/MutableStatWithTempMods;");
        MappingEntry marketAdvance = lookup.requireMethodByNamedName(
                "com/fs/starfarer/campaign/econ/Market",
                "advance",
                "(F)V");

        assertEquals("com/fs/starfarer/campaign/econ/CommodityOnMarket", commodityClass.obfuscatedName());
        assertEquals("reapplyEventMod", reapplyEventMod.obfuscatedName());
        assertEquals("getAvailableStat", getAvailableStat.obfuscatedName());
        assertEquals("advance", marketAdvance.obfuscatedName());
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

        // endScreenOverlay 和 getBoolean 的混淆名在 Linux / Windows 间互换
        boolean isWindows = MappingPlatform.current() == MappingPlatform.WINDOWS;
        String expectedEndOverlay = isWindows ? "Õ00000" : "class";
        String expectedGetBoolean = isWindows ? "class" : "Õ00000";

        assertEquals("o00000", beginOverlay.obfuscatedName());
        assertEquals(expectedEndOverlay, endOverlay.obfuscatedName());
        assertEquals(expectedGetBoolean, getBoolean.obfuscatedName());
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
                // EngineGlowType#PRIMARY 的 obf 名双平台不同（混淆器各自命名，见
                // docs/design/dev-environment-mapping-workflow.md 双平台取证纪律）：
                // linux 为 Object，windows 为 Ó00000；测试随运行平台加载对应表，断言二者之一。
                assertTrue(Set.of("Object", "Ó00000").contains(primaryGlowType.obfuscatedName()),
                        "EngineGlowType#PRIMARY obf 名应为平台真实值，实际: " + primaryGlowType.obfuscatedName());
        }

    @Test
    void missingMethodMappingReportsReadableError() {
        MappingLookup lookup = new MappingLookup(TinyV2MappingRepository.loadDefault());

        MappingLookupException exception = assertThrows(MappingLookupException.class,
                () -> lookup.requireMethodByNamedName("com/fs/graphics/TextureLoader", "missingMethod", "()V"));
        assertEquals("未找到方法映射: com/fs/graphics/TextureLoader#missingMethod()V", exception.getMessage());
    }
}
