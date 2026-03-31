package github.kasuminova.ssoptimizer.font;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TtfBmFontGeneratorTest {
    @Test
    void expandsRuntimeAtlasDimensionsInSmallRectangularSteps() {
        final OriginalGameFontOverrides.FontOverrideSpec baseSpec = new OriginalGameFontOverrides.FontOverrideSpec(
                "graphics/fonts/insignia21LTaa.fnt",
                List.of("lte50549.ttf"),
                List.of("MiSans-Regular.ttf"),
                2048,
                1024
        );

        final OriginalGameFontOverrides.FontOverrideSpec scale1000 = TtfBmFontGenerator.runtimeSpecForScale(
                baseSpec,
                "ssoptimizer/runtimefonts/graphics/fonts/insignia21LTaa_s1000.fnt",
                1.0f
        );
        assertEquals(2048, scale1000.pageWidth());
        assertEquals(1024, scale1000.pageHeight());

        final OriginalGameFontOverrides.FontOverrideSpec scale1500 = TtfBmFontGenerator.runtimeSpecForScale(
                baseSpec,
                "ssoptimizer/runtimefonts/graphics/fonts/insignia21LTaa_s1500.fnt",
                1.5f
        );
        assertEquals(3072, scale1500.pageWidth());
        assertEquals(1536, scale1500.pageHeight());

        final OriginalGameFontOverrides.FontOverrideSpec scale3125 = TtfBmFontGenerator.runtimeSpecForScale(
                baseSpec,
                "ssoptimizer/runtimefonts/graphics/fonts/insignia21LTaa_s3125.fnt",
                3.125f
        );
        assertEquals(6400, scale3125.pageWidth());
        assertEquals(3200, scale3125.pageHeight());

        final OriginalGameFontOverrides.FontOverrideSpec scale4000 = TtfBmFontGenerator.runtimeSpecForScale(
                new OriginalGameFontOverrides.FontOverrideSpec(
                        "graphics/fonts/orbitron24aabold.fnt",
                        List.of("orbitron-bold.ttf"),
                        List.of("MiSans-Regular.ttf"),
                        2048,
                        2048
                ),
                "ssoptimizer/runtimefonts/graphics/fonts/orbitron24aabold_s4000.fnt",
                4.0f
        );
        assertEquals(8192, scale4000.pageWidth());
        assertEquals(8192, scale4000.pageHeight());
    }

    @Test
    void prefersSourceAtlasDimensionsWhenProvided() {
        final OriginalGameFontOverrides.FontOverrideSpec baseSpec = new OriginalGameFontOverrides.FontOverrideSpec(
                "graphics/fonts/orbitron20bold.fnt",
                List.of("orbitron-bold.ttf"),
                List.of("MiSans-Regular.ttf"),
                2048,
                2048
        );

        final OriginalGameFontOverrides.FontOverrideSpec runtimeSpec = TtfBmFontGenerator.runtimeSpecForScale(
                baseSpec,
                "ssoptimizer/runtimefonts/graphics/fonts/orbitron20bold_s1500.fnt",
                1.5f,
                256,
                256
        );

        assertEquals(384, runtimeSpec.pageWidth());
        assertEquals(384, runtimeSpec.pageHeight());
    }

    @Test
    void doesNotDoubleScaleAlreadyScaledSourceAtlasWhenFitting() {
        final OriginalGameFontOverrides.FontOverrideSpec baseSpec = new OriginalGameFontOverrides.FontOverrideSpec(
                "graphics/fonts/orbitron24aabold.fnt",
                List.of("orbitron-bold.ttf"),
                List.of("MiSans-Regular.ttf"),
                2048,
                2048
        );

        final OriginalGameFontOverrides.FontOverrideSpec fitStartSpec = TtfBmFontGenerator.fitStartSpec(
                baseSpec,
                "ssoptimizer/runtimefonts/graphics/fonts/orbitron24aabold_s1500.fnt",
                3072,
                3072
        );

        assertEquals(3072, fitStartSpec.pageWidth());
        assertEquals(3072, fitStartSpec.pageHeight());
    }

    @Test
    void compactsSinglePageAtlasToUsedBounds() {
        assertEquals(1285, TtfBmFontGenerator.compactPageDimension(3072, 1285));
        assertEquals(3072, TtfBmFontGenerator.compactPageDimension(3072, 4096));
        assertEquals(3072, TtfBmFontGenerator.compactPageDimension(3072, 0));
    }

    @Test
    void preservesBlankPlaceholderGlyphMetricsFromSourceBmFont() {
        assertTrue(TtfBmFontGenerator.shouldPreserveSourceSpecialGlyph(0, 0));
        assertTrue(TtfBmFontGenerator.shouldPreserveSourceSpecialGlyph(1, 1));
        assertFalse(TtfBmFontGenerator.shouldPreserveSourceSpecialGlyph(3, 7));

        assertArrayEquals(
                new int[]{0, 0, 0, 0, 0},
                TtfBmFontGenerator.preservedSpecialGlyphMetrics(0, 0, 0, 0, 0)
        );
        assertArrayEquals(
                new int[]{1, 1, 0, 11, 15},
                TtfBmFontGenerator.preservedSpecialGlyphMetrics(1, 1, 0, 11, 15)
        );
        assertNull(TtfBmFontGenerator.preservedSpecialGlyphMetrics(4, 8, 1, 0, 6));
    }

    @Test
    void alignsRasterizedGlyphMetricsWithoutCroppingRenderedPixels() {
        assertArrayEquals(
                new int[]{6, 10, 0, 2, 9, 6, 10},
                TtfBmFontGenerator.alignedGlyphMetrics(5, 10, 0, 2, 5, 4, 9, 2, 3, 9)
        );
        assertArrayEquals(
                new int[]{9, 8, 0, 2, 9, 9, 8},
                TtfBmFontGenerator.alignedGlyphMetrics(9, 8, 0, 2, 8, 8, 8, 0, 2, 8)
        );
    }

        @Test
        void encodesXAdvanceAgainstGlyphOriginForRuntimeLayoutQuirk() {
                assertEquals(8, TtfBmFontGenerator.encodedXAdvanceForRuntimeLayout(8, 0));
                assertEquals(6, TtfBmFontGenerator.encodedXAdvanceForRuntimeLayout(8, 2));
                assertEquals(9, TtfBmFontGenerator.encodedXAdvanceForRuntimeLayout(8, -1));
                assertEquals(0, TtfBmFontGenerator.encodedXAdvanceForRuntimeLayout(2, 5));
        }

    @Test
    void parsesLegacyEncodedBmFontMetadataWithoutUtf8Failure() throws Exception {
        final Path tempFile = Files.createTempFile("ssoptimizer-font", ".fnt");
        try {
            final String content = "info face=\"方正兰亭中粗黑简体_特殊变种1\" size=15 bold=0 italic=0 charset=\"\" unicode=1 smooth=0 aa=0\r\n"
                    + "common lineHeight=19 base=15 scaleW=512 scaleH=512 pages=1 packed=0\r\n"
                    + "page id=0 file=\"insignia15LTaa_0.png\"\r\n"
                    + "chars count=1\r\n"
                    + "char id=65 x=1 y=2 width=3 height=4 xoffset=0 yoffset=1 xadvance=6 page=0 chnl=0\r\n";
            Files.write(tempFile, content.getBytes(Charset.forName("GBK")));

            final Class<?> sourceClass = Class.forName("github.kasuminova.ssoptimizer.font.TtfBmFontGenerator$SourceBmFont");
            final Method parse = sourceClass.getDeclaredMethod("parse", Path.class);
            parse.setAccessible(true);
            final Object source = parse.invoke(null, tempFile);

            assertNotNull(source);
            assertEquals(15, invokeInt(source, "infoSize"));
            assertFalse(invokeBoolean(source, "antiAlias"));
            assertEquals(19, invokeInt(source, "lineHeight"));
            assertEquals(15, invokeInt(source, "base"));
            assertEquals(512, invokeInt(source, "scaleWidth"));
            assertEquals(512, invokeInt(source, "scaleHeight"));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void clampsFallbackVisualScaleFactorIntoSafeRange() {
        assertEquals(1.0f, TtfBmFontGenerator.fallbackVisualScaleFactor(12f, 0f));
        assertEquals(1.0f, TtfBmFontGenerator.fallbackVisualScaleFactor(Float.NaN, 10f));
        assertEquals(1.36f, TtfBmFontGenerator.fallbackVisualScaleFactor(20f, 10f));
        assertEquals(0.88f, TtfBmFontGenerator.fallbackVisualScaleFactor(8f, 20f));

        final float balanced = TtfBmFontGenerator.fallbackVisualScaleFactor(12f, 11f);
        assertTrue(balanced > 1.0f && balanced < 1.36f);
    }

    @Test
    void clampsPrimaryAdvanceScaleFactorIntoSafeRange() {
        assertEquals(1.0f, TtfBmFontGenerator.primaryAdvanceScaleFactor(12f, 0f));
        assertEquals(1.0f, TtfBmFontGenerator.primaryAdvanceScaleFactor(Float.NaN, 10f));
        assertEquals(0.88f, TtfBmFontGenerator.primaryAdvanceScaleFactor(10f, 20f));
        assertEquals(1.08f, TtfBmFontGenerator.primaryAdvanceScaleFactor(24f, 12f));

        final float shrink = TtfBmFontGenerator.primaryAdvanceScaleFactor(11.67f, 13.5f);
        assertTrue(shrink < 1.0f && shrink >= 0.88f);
    }

    @Test
    void computesFallbackTargetMetricFromOriginalRatio() {
        assertEquals(0f, TtfBmFontGenerator.fallbackTargetMetric(10f, 0f, 15f));
        assertEquals(0f, TtfBmFontGenerator.fallbackTargetMetric(Float.NaN, 12f, 15f));
        assertEquals(12.5f, TtfBmFontGenerator.fallbackTargetMetric(10f, 12f, 15f));
        assertEquals(15f, TtfBmFontGenerator.fallbackTargetMetric(11.67f, 11.67f, 15f), 0.01f);
    }

    private int invokeInt(final Object target,
                          final String methodName) throws Exception {
        final Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (Integer) method.invoke(target);
    }

    private boolean invokeBoolean(final Object target,
                                  final String methodName) throws Exception {
        final Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (Boolean) method.invoke(target);
    }
}