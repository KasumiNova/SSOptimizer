package github.kasuminova.ssoptimizer.font;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeScaledFontCacheTest {
    @Test
    void scalesRequestedFontSizeByScreenScaleForRuntimeAtlasSelection() {
        assertEquals(22.5f, RuntimeScaledFontCache.effectiveRequestedFontSize(15.0f, 1.5f));
        assertEquals(15.0f, RuntimeScaledFontCache.effectiveRequestedFontSize(15.0f, Float.NaN));
        assertEquals(15.0f, RuntimeScaledFontCache.effectiveRequestedFontSize(15.0f, 0.0f));
    }

    @Test
    void computesEffectiveBaseScaleAgainstOriginalNominalSize() {
        assertEquals(1.5f, RuntimeScaledFontCache.effectiveBaseScale(20.0f, 20, 1.5f));
        assertEquals(1.25f, RuntimeScaledFontCache.effectiveBaseScale(15.0f, 12, 1.0f));
        assertEquals(1.0f, RuntimeScaledFontCache.effectiveBaseScale(Float.NaN, 15, 1.5f));
    }

    @Test
    void derivesScaleBucketFromCurrentNominalSize() {
        assertEquals(1500, RuntimeScaledFontCache.scaleBucketMillis(30, 20));
        assertEquals(2250, RuntimeScaledFontCache.scaleBucketMillis(45, 20));
        assertEquals(1000, RuntimeScaledFontCache.scaleBucketMillis(0, 20));
    }

    @Test
    void reusesCurrentFontWhenItsNominalSizeAlreadyMatchesEffectiveScaleTarget() {
        assertEquals(
                RuntimeScaledFontCache.ScaleResolution.CURRENT,
                RuntimeScaledFontCache.resolveScaleResolution(20, 30, 1000, 1.5f)
        );
        assertEquals(
                RuntimeScaledFontCache.ScaleResolution.RUNTIME,
                RuntimeScaledFontCache.resolveScaleResolution(20, 20, 1000, 1.5f)
        );
        assertEquals(
                RuntimeScaledFontCache.ScaleResolution.BASE,
                RuntimeScaledFontCache.resolveScaleResolution(20, 30, 1000, 1.0f)
        );
    }

    @Test
    void pinsRuntimeAtlasBucketToScreenScaleInsteadOfEffectSize() {
        assertEquals(1500, RuntimeScaledFontCache.targetScaleBucketMillis(1000, 1.5f));
        assertEquals(1500, RuntimeScaledFontCache.targetScaleBucketMillis(1000, 1.49f));
        assertEquals(1000, RuntimeScaledFontCache.targetScaleBucketMillis(1000, 1.0f));
        assertEquals(1000, RuntimeScaledFontCache.targetScaleBucketMillis(1000, Float.NaN));
        assertEquals(1.5f, RuntimeScaledFontCache.targetScaleBucket(1000, 1.5f));
    }

    @Test
    void doesNotNormalizeRequestedFontSizeForBaseOverrideFonts() {
        assertFalse(RuntimeScaledFontCache.shouldNormalizeRequestedFontSize(
                "graphics/fonts/orbitron24aabold.fnt", 20, 30, 30.0f, 1.5f
        ));
        assertEquals(
                30.0f,
                RuntimeScaledFontCache.normalizeRequestedFontSize("graphics/fonts/orbitron24aabold.fnt", 20, 30, 30.0f, 1.5f)
        );
        assertEquals(
                20.0f,
                RuntimeScaledFontCache.normalizeRequestedFontSize("graphics/fonts/orbitron24aabold.fnt", 20, 20, 20.0f, 1.5f)
        );
        assertEquals(
                20.0f,
                RuntimeScaledFontCache.normalizeRequestedFontSize("graphics/fonts/orbitron24aabold.fnt", 20, 30, 20.0f, 1.5f)
        );
    }

    @Test
    void normalizesRequestedFontSizeForRuntimeFontsOnlyWhenStillScaledByScreenScale() {
        assertTrue(RuntimeScaledFontCache.shouldNormalizeRequestedFontSize(
                "ssoptimizer/runtimefonts/graphics/fonts/orbitron24aabold_s1000.fnt", 20, 20, 30.0f, 1.5f
        ));
        assertEquals(
                20.0f,
                RuntimeScaledFontCache.normalizeRequestedFontSize(
                        "ssoptimizer/runtimefonts/graphics/fonts/orbitron24aabold_s1000.fnt", 20, 20, 30.0f, 1.5f
                )
        );
        assertFalse(RuntimeScaledFontCache.shouldNormalizeRequestedFontSize(
                "ssoptimizer/runtimefonts/graphics/fonts/orbitron24aabold_s625.fnt", 20, 13, 13.333f, 1.5f
        ));
        assertEquals(
                13.333f,
                RuntimeScaledFontCache.normalizeRequestedFontSize(
                        "ssoptimizer/runtimefonts/graphics/fonts/orbitron24aabold_s625.fnt", 20, 13, 13.333f, 1.5f
                )
        );
    }

    @Test
    void buildsStableVirtualRuntimeFontPath() {
        assertEquals(
                "ssoptimizer/runtimefonts/graphics/fonts/orbitron16_s1250.fnt",
                RuntimeScaledFontCache.buildRuntimeFontPath("graphics/fonts/orbitron16.fnt", 1250)
        );
        assertEquals(
                "ssoptimizer/runtimefonts/graphics/fonts/insignia21LTaa_s1000.fnt",
                RuntimeScaledFontCache.buildRuntimeFontPath("/graphics/fonts/insignia21LTaa.fnt", 1000)
        );
    }

    @Test
    void recognizesRuntimeFontPathPrefix() {
        assertTrue(RuntimeScaledFontCache.isRuntimeFontPath("ssoptimizer/runtimefonts/graphics/fonts/orbitron16_s1250.fnt"));
        assertFalse(RuntimeScaledFontCache.isRuntimeFontPath("graphics/fonts/orbitron16.fnt"));
    }

    @Test
    void exposesRuntimeGeneratedResourcesUnderRelativeAtlasAliases() throws Exception {
        RuntimeScaledFontCache.clearGeneratedResources();
        final byte[] atlasBytes = "atlas".getBytes(StandardCharsets.UTF_8);

        RuntimeScaledFontCache.registerGeneratedResource(
                "ssoptimizer/runtimefonts/graphics/fonts/orbitron24aabold_s1500_0.png",
                atlasBytes
        );

        assertTrue(RuntimeScaledFontCache.generatedResourceAliases(
                "ssoptimizer/runtimefonts/graphics/fonts/orbitron24aabold_s1500_0.png"
        ).containsAll(Set.of(
                "ssoptimizer/runtimefonts/graphics/fonts/orbitron24aabold_s1500_0.png",
                "graphics/fonts/orbitron24aabold_s1500_0.png",
                "orbitron24aabold_s1500_0.png"
        )));

        assertNotNull(RuntimeScaledFontCache.openGeneratedStream(
                "ssoptimizer/runtimefonts/graphics/fonts/orbitron24aabold_s1500_0.png"
        ));
        assertNotNull(RuntimeScaledFontCache.openGeneratedStream(
                "graphics/fonts/orbitron24aabold_s1500_0.png"
        ));
        assertNotNull(RuntimeScaledFontCache.openGeneratedStream(
                "orbitron24aabold_s1500_0.png"
        ));
    }

    @Test
    void keepsNonRuntimeResourcesOnExactPathOnly() {
        assertEquals(
                Set.of("graphics/fonts/orbitron24aabold_0.png"),
                RuntimeScaledFontCache.generatedResourceAliases("graphics/fonts/orbitron24aabold_0.png")
        );
        assertNull(RuntimeScaledFontCache.openGeneratedStream("graphics/fonts/orbitron24aabold_0.png"));
    }
}