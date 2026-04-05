package github.kasuminova.ssoptimizer.common.loading;

import com.fs.graphics.TextureObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LazyTextureManagerTest {
    @AfterEach
    void tearDown() {
        System.clearProperty(LazyTextureManager.IDLE_UNLOAD_MILLIS_PROPERTY);
        System.clearProperty(LazyTextureManager.PREVIEW_PROTECT_MILLIS_PROPERTY);
        System.clearProperty(LazyTextureManager.MINIMAL_STARTUP_PROPERTY);
        System.clearProperty(LazyTextureManager.TRACK_MIN_GPU_BYTES_PROPERTY);
        System.clearProperty(LazyTextureManager.COMPOSITION_REPORT_FILE_PROPERTY);
        System.clearProperty(LazyTextureManager.COMPOSITION_REPORT_INTERVAL_MILLIS_PROPERTY);
        System.clearProperty(LazyTextureManager.MANAGEMENT_LOG_INTERVAL_MILLIS_PROPERTY);
        LazyTextureManager.clearContextTracking();
    }

    @Test
    void tracksPortraitTexturesForIdleUnloadWhenMinimalStartupDefersThem() {
        assertTrue(LazyTextureManager.shouldDefer("graphics/portraits/captain.png", 16_384, 65_536));
        assertTrue(LazyTextureManager.shouldTrackResidency("graphics/portraits/captain.png", 16_384, 65_536));
    }

    @Test
    void doesNotTrackAlwaysResidentUiTextures() {
        assertFalse(LazyTextureManager.shouldTrackResidency("graphics/ui/panel.png", 2_000_000, 2_000_000));
    }

    @Test
    void tracksWeaponTexturesForIdleUnloadEvenWhenInitialUploadIsEager() {
        assertTrue(LazyTextureManager.shouldDefer("graphics/weapons/railgun.png", 24_576, 65_536));
        assertTrue(LazyTextureManager.shouldTrackResidency("graphics/weapons/railgun.png", 24_576, 65_536));
    }

    @Test
    void minimalStartupDefersAllManagedGraphicsDirectoriesByDefault() {
        assertTrue(LazyTextureManager.shouldDefer("graphics/ships/frigate.png", 8_192, 262_144));
        assertTrue(LazyTextureManager.shouldDefer("graphics/shaders/distortions/wave.png", 8_192, 262_144));
        assertTrue(LazyTextureManager.shouldDefer("graphics/fx/ring.png", 8_192, 262_144));
        assertTrue(LazyTextureManager.shouldDefer("graphics/portraits/captain.png", 8_192, 262_144));
        assertTrue(LazyTextureManager.shouldDefer("graphics/weapons/railgun.png", 8_192, 262_144));
        assertTrue(LazyTextureManager.shouldDefer("graphics/backgrounds/hyperspace_bg_cool.jpg", 8_192, 262_144));
        assertTrue(LazyTextureManager.shouldDefer("graphics/planets/cryovolcanic01.jpg", 8_192, 262_144));
    }

    @Test
    void minimalStartupAlsoDefersNestedModGraphicsPaths() {
        assertTrue(LazyTextureManager.shouldDefer("graphics/tahlan/ships/legio/tahlan_imperator_nw.png", 8_192, 262_144));
        assertTrue(LazyTextureManager.shouldDefer("graphics/kk/portraits/characters/shacha.png", 8_192, 262_144));
        assertTrue(LazyTextureManager.shouldDefer("graphics/tahlan/maps/ships_glib/tahlan_scathach_material.png", 8_192, 262_144));
        assertTrue(LazyTextureManager.shouldDefer("graphics/tahlan/maps/ships_normals/tahlan_scathach_normal.png", 8_192, 262_144));
        assertTrue(LazyTextureManager.shouldDefer("graphics/jc/ships/sf/jc_sf_shadowlord/ship.png", 8_192, 262_144));
    }

    @Test
    void minimalStartupDefersIllustrationsAndFactionBanners() {
        assertTrue(LazyTextureManager.shouldDefer("graphics/illustrations/magnetar.jpg", 8_192, 262_144));
        assertTrue(LazyTextureManager.shouldDefer("graphics/factions/persean_league.png", 8_192, 262_144));
    }

    @Test
    void minimalStartupKeepsUiBucketsEager() {
        assertFalse(LazyTextureManager.shouldDefer("graphics/ui/panel.png", 8_192, 262_144));
        assertFalse(LazyTextureManager.shouldDefer("graphics/hud/widget.png", 8_192, 262_144));
        assertFalse(LazyTextureManager.shouldDefer("graphics/fonts/insignia.png", 8_192, 262_144));
        assertFalse(LazyTextureManager.shouldDefer("graphics/icons/icon.png", 8_192, 262_144));
    }

    @Test
    void minimalStartupCanBeDisabledForCompatibility() {
        System.setProperty(LazyTextureManager.MINIMAL_STARTUP_PROPERTY, "false");

        assertFalse(LazyTextureManager.shouldDefer("graphics/ships/frigate.png", 8_192, 262_144));
        assertFalse(LazyTextureManager.shouldDefer("graphics/fx/ring.png", 8_192, 262_144));
        assertFalse(LazyTextureManager.shouldDefer("graphics/weapons/railgun.png", 8_192, 262_144));
    }

    @Test
    void tinyEffectsCanStayOutsideResidencyTrackingFloor() {
        assertFalse(LazyTextureManager.shouldTrackResidency("graphics/fx/spark.png", 4_096, 8_192));
    }

    @Test
    void nonGraphicsPathsStayOutsideManagedResidencyTracking() {
        assertFalse(LazyTextureManager.shouldDefer("data/ships/hull.csv", 8_192, 262_144));
        assertFalse(LazyTextureManager.shouldTrackResidency("data/ships/hull.csv", 8_192, 262_144));
    }

    @Test
    void idleUnloadIsDisabledByDefaultForSafety() {
        assertEquals(0L, LazyTextureManager.idleUnloadMillis());
    }

    @Test
    void idleUnloadMillisCanBeDisabledViaProperty() {
        System.setProperty(LazyTextureManager.IDLE_UNLOAD_MILLIS_PROPERTY, "0");

        assertEquals(0L, LazyTextureManager.idleUnloadMillis());
    }

    @Test
    void previewSensitiveTexturesUseExtendedIdleGraceWindow() {
        System.setProperty(LazyTextureManager.IDLE_UNLOAD_MILLIS_PROPERTY, "60000");
        System.setProperty(LazyTextureManager.PREVIEW_PROTECT_MILLIS_PROPERTY, "240000");

        assertTrue(LazyTextureManager.isPreviewProtectedTexture("graphics/ships/frigate.png"));
        assertTrue(LazyTextureManager.isPreviewProtectedTexture("graphics/stations/battlestation.png"));
        assertTrue(LazyTextureManager.isPreviewProtectedTexture("graphics/weapons/railgun.png"));
        assertFalse(LazyTextureManager.isPreviewProtectedTexture("graphics/portraits/captain.png"));

        assertEquals(240_000L, LazyTextureManager.effectiveIdleUnloadMillis("graphics/ships/frigate.png"));
        assertEquals(240_000L, LazyTextureManager.effectiveIdleUnloadMillis("graphics/weapons/railgun.png"));
        assertEquals(60_000L, LazyTextureManager.effectiveIdleUnloadMillis("graphics/portraits/captain.png"));
    }

    @Test
    void trackMinimumGpuBytesCanBeConfigured() {
        System.setProperty(LazyTextureManager.TRACK_MIN_GPU_BYTES_PROPERTY, "131072");

        assertEquals(131_072L, LazyTextureManager.trackMinimumGpuBytes());
    }

    @Test
    void compositionReportIntervalCanBeDisabled() {
        System.setProperty(LazyTextureManager.COMPOSITION_REPORT_INTERVAL_MILLIS_PROPERTY, "0");

        assertEquals(0L, LazyTextureManager.compositionReportIntervalMillis());
    }

    @Test
    void compositionReportDefaultsToTsvFile() {
        assertEquals(LazyTextureManager.DEFAULT_COMPOSITION_REPORT_FILE,
                LazyTextureManager.configuredCompositionReportPath());
    }

    @Test
    void minimalStartupIsEnabledByDefault() {
        assertTrue(LazyTextureManager.minimalStartupEnabled());
    }

    @Test
    void managementLogIntervalCanBeConfigured() {
        System.setProperty(LazyTextureManager.MANAGEMENT_LOG_INTERVAL_MILLIS_PROPERTY, "0");

        assertEquals(0L, LazyTextureManager.managementLogIntervalMillis());
    }

    @Test
    void estimatedGpuBytesUseRgbaStorageForNonAlphaTextures() {
        final long estimated = LazyTextureManager.estimateTextureGpuBytes(
                "graphics/weapons/railgun.png",
                2048,
                2048,
                2048,
                2048);

        assertEquals(2048L * 2048L * 4L, estimated);
    }

    @Test
    void estimatedGpuBytesIncludeMipmapsForSmallTextures() {
        final long estimated = LazyTextureManager.estimateTextureGpuBytes(
                "graphics/weapons/railgun.png",
                512,
                512,
                512,
                512);

        assertEquals(1_398_100L, estimated);
    }

    @Test
    void victorFontsUseLinearFilteringWithoutMipmaps() {
        assertFalse(LazyTextureManager.isVictorPixelFontTexture("graphics/fonts/victor14_0.png"));
        assertFalse(LazyTextureManager.isVictorPixelFontTexture("ssoptimizer/runtimefonts/graphics/fonts/victor14_s1500_0.png"));
        assertTrue(LazyTextureManager.isManagedVictorFontTexture("graphics/fonts/victor14_0.png"));
        assertTrue(LazyTextureManager.isManagedVictorFontTexture("ssoptimizer/runtimefonts/graphics/fonts/victor14_s1500_0.png"));
        assertEquals(9729, LazyTextureManager.minFilterForResourcePath("graphics/fonts/victor14_0.png"));
        assertEquals(9729, LazyTextureManager.magFilterForResourcePath("graphics/fonts/victor14_0.png"));

        final long estimated = LazyTextureManager.estimateTextureGpuBytes(
                "graphics/fonts/victor14_0.png",
                512,
                512,
                512,
                512);
        assertEquals(512L * 512L * 4L, estimated);
    }

    @Test
    void nonPixelUiFontsKeepLinearFiltering() {
        assertEquals(9729, LazyTextureManager.minFilterForResourcePath("graphics/fonts/insignia21LTaa_0.png"));
        assertEquals(9729, LazyTextureManager.magFilterForResourcePath("graphics/fonts/orbitron24aa_0.png"));
        assertFalse(LazyTextureManager.isVictorPixelFontTexture("graphics/fonts/orbitron24aa_0.png"));

        final long estimated = LazyTextureManager.estimateTextureGpuBytes(
                "graphics/fonts/orbitron24aa_0.png",
                512,
                512,
                512,
                512);
        assertEquals(512L * 512L * 4L, estimated);
    }

    @Test
    void generatedUiFontsDisableMipmapsWithoutUsingNearest() {
        assertTrue(LazyTextureManager.isSharpenedUiFontTexture("graphics/fonts/insignia21LTaa_0.png"));
        assertTrue(LazyTextureManager.isSharpenedUiFontTexture("graphics/fonts/orbitron24aa_0.png"));
        assertTrue(LazyTextureManager.isSharpenedUiFontTexture("ssoptimizer/runtimefonts/graphics/fonts/orbitron24aa_s1250_0.png"));
        assertTrue(LazyTextureManager.isManagedVictorFontTexture("ssoptimizer/runtimefonts/graphics/fonts/victor10_s1500_0.png"));
        assertTrue(LazyTextureManager.isManagedFontTexture("ssoptimizer/runtimefonts/graphics/fonts/victor10_s1500_0.png"));
        assertEquals(9729, LazyTextureManager.minFilterForResourcePath("graphics/fonts/insignia21LTaa_0.png"));
        assertEquals(9729, LazyTextureManager.magFilterForResourcePath("graphics/fonts/insignia21LTaa_0.png"));
    }

    @Test
    void contextReloadTracksEagerFontAtlasesEvenWithoutResidencyTracking() {
        assertFalse(LazyTextureManager.shouldTrackResidency("graphics/fonts/orbitron24aa_0.png", 2_000_000, 2_000_000));
        assertTrue(LazyTextureManager.shouldTrackContextBoundTexture("graphics/fonts/orbitron24aa_0.png"));
        assertTrue(LazyTextureManager.shouldTrackContextBoundTexture("ssoptimizer/runtimefonts/graphics/fonts/orbitron24aa_s1500_0.png"));
    }

    @Test
    void contextReloadTriggersWhenTextureWasLoadedInOlderGeneration() {
        final TextureObject texture = new TextureObject(3553, 42, "graphics/fonts/orbitron24aa_0.png");

        LazyTextureManager.noteTextureLoadedForContext(texture, "graphics/fonts/orbitron24aa_0.png", 1L);

        assertEquals(1L, LazyTextureManager.trackedContextGeneration(texture));
        assertTrue(LazyTextureManager.requiresContextReload(texture, 2L));
        assertFalse(LazyTextureManager.requiresContextReload(texture, 1L));
    }

    @Test
    void getTextureIdBypassesContextReloadWhileReloadIsAlreadyInProgress() {
        final TextureObject texture = new TextureObject(3553, 42, "graphics/fonts/orbitron24aa_0.png");

        final int id = LazyTextureManager.withContextReloadGuard(texture,
                () -> LazyTextureManager.getTextureId(texture, 3553, 42));

        assertEquals(42, id);
    }

    @Test
    void getTextureIdMarksManagedTextureAsNonEvictableAfterIdEscapes() {
        final TextureObject texture = new TextureObject(3553, 42, "graphics/weapons/railgun.png");
        LazyTextureManager.trackResidentTextureForTests(texture, "graphics/weapons/railgun.png");

        assertTrue(LazyTextureManager.isTextureEvictable(texture));
        assertEquals(42, LazyTextureManager.getTextureId(texture, 3553, 42));
        assertFalse(LazyTextureManager.isTextureEvictable(texture));
    }

    @Test
    void managementSummaryIncludesEvictionsAndResidentGroups() {
        final String summary = LazyTextureManager.formatManagementSummary(List.of(
                new TextureCompositionReport.TextureEntry(
                        "graphics/ships/capital.png",
                        "resident",
                        true,
                        10,
                        1000,
                        2048,
                        2048,
                        2048,
                        2048,
                        8L << 20,
                        7,
                        "ship"
                ),
                new TextureCompositionReport.TextureEntry(
                        "graphics/weapons/railgun.png",
                        "resident",
                        true,
                        5,
                        1200,
                        512,
                        512,
                        512,
                        512,
                        1L << 20,
                        9,
                        "weapon"
                ),
                new TextureCompositionReport.TextureEntry(
                        "graphics/portraits/captain.png",
                        "deferred-awaiting-first-bind",
                        true,
                        0,
                        60_000,
                        1024,
                        1024,
                        1024,
                        1024,
                        4L << 20,
                        -1,
                        "portrait"
                )
        ), 2L, 5L);

        assertTrue(summary.contains("tracked=3"));
        assertTrue(summary.contains("resident=2"));
        assertTrue(summary.contains("nonResident=1"));
        assertTrue(summary.contains("recentlyEvicted=2"));
        assertTrue(summary.contains("totalEvicted=5"));
        assertTrue(summary.contains("graphics/ships=8.0MiB"));
        assertTrue(summary.contains("graphics/weapons=1.0MiB"));
    }
}