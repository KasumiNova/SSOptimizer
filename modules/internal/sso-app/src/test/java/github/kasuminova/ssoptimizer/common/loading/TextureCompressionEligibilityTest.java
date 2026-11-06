package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.loading.TextureCompressionSupport.Format;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextureCompressionEligibilityTest {
    private static final String SHIP = "graphics/ships/kite.png";

    @Test
    void smallTexturesAreExcluded() {
        assertEquals(Format.NONE, select("graphics/icons/icon.png", 32, 32, AlphaKind.FULL, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/icons/icon.png", 63, 63, AlphaKind.OPAQUE, Format.BC7, true, false));
        assertEquals(Format.NONE, select(SHIP, 64, 63, AlphaKind.OPAQUE, Format.BC7, true, false));
        assertEquals(Format.BC7, select(SHIP, 64, 64, AlphaKind.FULL, Format.BC7, true, false));
    }

    @Test
    void materialSuffixesAreExcluded() {
        assertEquals(Format.NONE, select("graphics/ships/kite_normal.png", 512, 512, AlphaKind.OPAQUE, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/ships/kite_surface.png", 512, 512, AlphaKind.FULL, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/ships/kite_material.png", 512, 512, AlphaKind.OPAQUE, Format.BC3, true, false));
        assertEquals(Format.NONE, select("graphics/ships/KITE_NORMAL.PNG", 512, 512, AlphaKind.OPAQUE, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/ships/kite_Normal.tga", 512, 512, AlphaKind.OPAQUE, Format.BC7, true, false));
        assertEquals(Format.BC7, select("graphics/normal_maps/kite.png", 512, 512, AlphaKind.OPAQUE, Format.BC7, true, false));
    }

    @Test
    void fontAtlasesAreExcluded() {
        assertEquals(Format.NONE, select("graphics/fonts/insignia15.png", 512, 512, AlphaKind.FULL, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/fonts/orbitron12.png", 512, 512, AlphaKind.FULL, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/fonts/victor12.png", 512, 512, AlphaKind.FULL, Format.BC7, true, false));
        assertEquals(Format.NONE, select("ssoptimizer/runtimefonts/graphics/fonts/insignia15.png",
                512, 512, AlphaKind.FULL, Format.BC7, true, false));
    }

    @Test
    void bptcAvailablePrefersBc7ForEverything() {
        System.setProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY, "");
        try {
            assertEquals(Format.BC7, select("graphics/backgrounds/nebula.png", 2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, false));
            assertEquals(Format.BC7, select(SHIP, 256, 256, AlphaKind.OPAQUE, Format.BC7, true, false));
            assertEquals(Format.BC7, select(SHIP, 512, 512, AlphaKind.BINARY, Format.BC7, true, false));
            assertEquals(Format.BC7, select(SHIP, 512, 512, AlphaKind.FULL, Format.BC7, true, false));
            assertEquals(Format.BC7, select(SHIP, 512, 512, null, Format.BC7, true, false));
        } finally {
            System.clearProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY);
        }
    }

    @Test
    void bc1ForOpaquePropertyOptsIntoBc1ForOpaqueLarge() {
        System.setProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY, "");
        try {
            assertEquals(Format.BC1, select("graphics/backgrounds/nebula.png", 2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, true));
            assertEquals(Format.BC1, select(SHIP, 256, 256, AlphaKind.OPAQUE, Format.BC7, true, true));
            assertEquals(Format.BC7, select(SHIP, 512, 512, AlphaKind.BINARY, Format.BC7, true, true));
            assertEquals(Format.BC7, select(SHIP, 512, 512, AlphaKind.FULL, Format.BC7, true, true));
            assertEquals(Format.BC7, select(SHIP, 255, 255, AlphaKind.OPAQUE, Format.BC7, true, true));
            assertEquals(Format.BC7, select(SHIP, 512, 512, AlphaKind.OPAQUE, Format.BC7, false, true));
        } finally {
            System.clearProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY);
        }
    }

    @Test
    void defaultExcludePathsCoverBackgroundsAndFx() {
        assertEquals(Format.NONE, select("graphics/backgrounds/nebula.png", 2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/starscape/bg.png", 2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/illustrations/rat_genesis.jpg", 2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/fx/explosion_large.png", 512, 512, AlphaKind.FULL, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/aEP_Background/x.png", 1024, 1024, AlphaKind.OPAQUE, Format.BC7, true, false));
        assertEquals(Format.BC7, select("graphics/sfx/beam.png", 512, 512, AlphaKind.FULL, Format.BC7, true, false));
        assertEquals(Format.BC7, select(SHIP, 512, 512, AlphaKind.FULL, Format.BC7, true, false));
    }

    @Test
    void s3tcFallbackSelectsByActualAlphaContent() {
        assertEquals(Format.BC1, select(SHIP, 2048, 2048, AlphaKind.OPAQUE, Format.BC3, true, false));
        assertEquals(Format.BC1, select(SHIP, 512, 512, AlphaKind.BINARY, Format.BC3, true, false));
        assertEquals(Format.BC3, select(SHIP, 512, 512, AlphaKind.FULL, Format.BC3, true, false));
        assertEquals(Format.BC3, select(SHIP, 255, 255, AlphaKind.OPAQUE, Format.BC3, true, false));
        assertEquals(Format.BC3, select(SHIP, 128, 128, AlphaKind.BINARY, Format.BC3, true, false));
        assertEquals(Format.BC3, select(SHIP, 512, 512, AlphaKind.OPAQUE, Format.BC3, false, false));
        assertEquals(Format.NONE, select(SHIP, 512, 512, AlphaKind.OPAQUE, Format.NONE, false, false));
    }

    @Test
    void preferredFormatPassesThrough() {
        assertEquals(Format.BC7, select(SHIP, 128, 128, AlphaKind.FULL, Format.BC7, true, false));
        assertEquals(Format.NONE, select(SHIP, 512, 512, AlphaKind.FULL, Format.NONE, false, false));
    }

    @Test
    void punchThroughAlphaOnlyForBinaryBc1() {
        assertTrue(TextureCompressionEligibility.usesPunchThroughAlpha(Format.BC1, AlphaKind.BINARY));
        assertFalse(TextureCompressionEligibility.usesPunchThroughAlpha(Format.BC1, AlphaKind.OPAQUE));
        assertFalse(TextureCompressionEligibility.usesPunchThroughAlpha(Format.BC1, AlphaKind.FULL));
        assertFalse(TextureCompressionEligibility.usesPunchThroughAlpha(Format.BC7, AlphaKind.BINARY));
        assertFalse(TextureCompressionEligibility.usesPunchThroughAlpha(Format.BC3, AlphaKind.BINARY));
        assertFalse(TextureCompressionEligibility.usesPunchThroughAlpha(Format.NONE, AlphaKind.BINARY));
    }

    @Test
    void resolveCompressedUploadOnlyHitsWhenEligibleAndCached() {
        final SsobcContainer container = SsobcContainer.parse(SsobcTestContainers.buildFullChain(
                NativeTextureCompressor.FORMAT_BC7, 128, 128));
        assertNotNull(container);

        assertSame(container, TextureCompressionEligibility.resolveCompressedUpload(
                SHIP, 128, 128, AlphaKind.FULL, Format.BC7, true, "hash", true, key -> container));
        assertNull(TextureCompressionEligibility.resolveCompressedUpload(
                SHIP, 128, 128, AlphaKind.FULL, Format.BC7, true, "hash", true, key -> null));
        assertNull(TextureCompressionEligibility.resolveCompressedUpload(
                "graphics/icons/icon.png", 32, 32, AlphaKind.FULL, Format.BC7, true, "hash", true, key -> container));
        assertNull(TextureCompressionEligibility.resolveCompressedUpload(
                SHIP, 128, 128, AlphaKind.FULL, Format.BC7, true, null, true, key -> container));
        assertNull(TextureCompressionEligibility.resolveCompressedUpload(
                SHIP, 128, 128, AlphaKind.FULL, Format.BC7, true, "  ", true, key -> container));
    }

    private static Format select(final String path,
                                 final int width,
                                 final int height,
                                 final AlphaKind alphaKind,
                                 final Format preferred,
                                 final boolean s3tc,
                                 final boolean bc1ForOpaque) {
        return TextureCompressionEligibility.selectFormat(path, width, height, alphaKind, preferred, s3tc, bc1ForOpaque);
    }

    @Test
    void excludePathsPropertyDisablesCompression() {
        System.setProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY, "backgrounds, fx/");
        try {
            assertEquals(Format.NONE, select("graphics/backgrounds/nebula.png",
                    2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, false));
            assertEquals(Format.NONE, select("graphics/BACKGROUNDS/Nebula.PNG",
                    2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, false));
            assertEquals(Format.BC7, select(SHIP, 512, 512, AlphaKind.FULL, Format.BC7, true, false));
        } finally {
            System.clearProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY);
        }
    }

    @Test
    void emptyExcludePathsPropertyKeepsEverythingEligible() {
        System.setProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY, "");
        try {
            assertEquals(Format.BC7, select("graphics/backgrounds/nebula.png",
                    2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, false));
        } finally {
            System.clearProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY);
        }
    }
}
