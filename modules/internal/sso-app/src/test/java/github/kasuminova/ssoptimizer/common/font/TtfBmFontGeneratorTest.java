package github.kasuminova.ssoptimizer.common.font;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TtfBmFontGeneratorTest {
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
        assertArrayEquals(
            new int[]{11, 15, 2, 2, 17, 11, 15},
                TtfBmFontGenerator.alignedGlyphMetrics(10, 15, 2, 2, 16, 11, 15, 2, 2, 17)
        );
    }

    @Test
    void encodesXAdvanceAgainstGlyphOriginForRuntimeLayoutQuirk() {
        assertEquals(8, TtfBmFontGenerator.encodedXAdvanceForRuntimeLayout(8, 0));
        assertEquals(6, TtfBmFontGenerator.encodedXAdvanceForRuntimeLayout(8, 2));
        assertEquals(9, TtfBmFontGenerator.encodedXAdvanceForRuntimeLayout(8, -1));
        assertEquals(0, TtfBmFontGenerator.encodedXAdvanceForRuntimeLayout(2, 5));

        assertEquals(8, TtfBmFontGenerator.decodedXAdvanceFromRuntimeLayout(8, 0));
        assertEquals(8, TtfBmFontGenerator.decodedXAdvanceFromRuntimeLayout(6, 2));
        assertEquals(8, TtfBmFontGenerator.decodedXAdvanceFromRuntimeLayout(9, -1));
        assertEquals(0, TtfBmFontGenerator.decodedXAdvanceFromRuntimeLayout(0, -5));
    }

    @Test
    void treatsAsciiBracesAsBlankSpaceGlyphs() {
        assertTrue(TtfBmFontGenerator.shouldTreatAsSpaceGlyph('{'));
        assertTrue(TtfBmFontGenerator.shouldTreatAsSpaceGlyph('}'));
        assertFalse(TtfBmFontGenerator.shouldTreatAsSpaceGlyph('A'));

        assertArrayEquals(
                new int[]{0, 0, 0, 0, 7},
                TtfBmFontGenerator.spaceEquivalentGlyphMetrics('{', 7)
        );
        assertArrayEquals(
                new int[]{0, 0, 0, 0, 7},
                TtfBmFontGenerator.spaceEquivalentGlyphMetrics('}', 7)
        );
        assertNull(TtfBmFontGenerator.spaceEquivalentGlyphMetrics('A', 7));
    }

    @Test
    void preservesControlCharsAsPlaceholderGlyphsWithoutRasterization() {
        // orbitron12condensed.fnt 的 id=0（NUL）/ id=29（GS）为 1×3 零墨迹占位符，应跳过栅格化
        assertTrue(TtfBmFontGenerator.shouldPreserveControlGlyph(0));
        assertTrue(TtfBmFontGenerator.shouldPreserveControlGlyph(29));
        assertTrue(TtfBmFontGenerator.shouldPreserveControlGlyph(31));
        // 空格（32）走正常栅格化（TTF 可显示、输出空墨迹）；
        // 可打印字符（如 victor10 的 1×3 冒号 id=58）不得被误判为占位符
        assertFalse(TtfBmFontGenerator.shouldPreserveControlGlyph(32));
        assertFalse(TtfBmFontGenerator.shouldPreserveControlGlyph(58));
        assertFalse(TtfBmFontGenerator.shouldPreserveControlGlyph(65));
    }

    @Test
    void substitutesVictorAsciiLowercaseWithUppercaseGlyphs() {
        assertEquals('A', TtfBmFontGenerator.substituteVictorLowercaseCodePoint('a'));
        assertEquals('Z', TtfBmFontGenerator.substituteVictorLowercaseCodePoint('z'));
        assertEquals('A', TtfBmFontGenerator.substituteVictorLowercaseCodePoint('A'));
        assertEquals('目', TtfBmFontGenerator.substituteVictorLowercaseCodePoint('目'));
    }

    @Test
    void detectsVictorManagedFontPaths() {
        assertTrue(TtfBmFontGenerator.isVictorManagedFontPath("graphics/fonts/victor10.fnt"));
        assertTrue(TtfBmFontGenerator.isVictorManagedFontPath("graphics/fonts/victor12.fnt"));
        assertTrue(TtfBmFontGenerator.isVictorManagedFontPath("graphics/fonts/victor14_0.png"));
        assertTrue(TtfBmFontGenerator.isVictorManagedFontPath("graphics/fonts/victor16.fnt"));
        assertTrue(TtfBmFontGenerator.isVictorManagedFontPath("graphics/fonts/victor21.fnt"));
        assertFalse(TtfBmFontGenerator.isVictorManagedFontPath("graphics/fonts/orbitron20aa.fnt"));
    }

    @Test
    void donatedCodePointsAreFilteredToPrintableBmpRange() {
        assertTrue(TtfBmFontGenerator.isDonatableCodePoint(0x4E2D));
        assertTrue(TtfBmFontGenerator.isDonatableCodePoint(32));
        assertFalse(TtfBmFontGenerator.isDonatableCodePoint(31));
        assertFalse(TtfBmFontGenerator.isDonatableCodePoint(0x7F));
        assertFalse(TtfBmFontGenerator.isDonatableCodePoint(0x1F600));
    }

    @Test
    void victorCenteredGlyphMetricsCenterInkWithinAdvanceCell() {
        // 'M'：墨迹 9×8 居中到 advance 11 → 左边距 1，advance 不膨胀
        assertArrayEquals(
                new int[]{9, 8, 1, 4, 11},
                TtfBmFontGenerator.victorCenteredGlyphMetrics(9, 8, 4, 8, 4, 11)
        );
        // 'I'：窄墨迹 2×8 居中到 advance 5 → 左边距 2（消除窄字后大空档）
        assertArrayEquals(
                new int[]{2, 8, 2, 4, 5},
                TtfBmFontGenerator.victorCenteredGlyphMetrics(2, 8, 4, 8, 4, 5)
        );
        // CJK：墨迹 12×12 居中到 advance 13 → 左边距 1；垂直并集保留源盒（yoffset 2）
        assertArrayEquals(
                new int[]{12, 12, 1, 2, 13},
                TtfBmFontGenerator.victorCenteredGlyphMetrics(12, 12, 2, 12, 2, 13)
        );
        // 墨迹宽于单元格：对称负溢出（advance 恒取源值）
        assertArrayEquals(
                new int[]{12, 8, -1, 4, 10},
                TtfBmFontGenerator.victorCenteredGlyphMetrics(12, 8, 4, 8, 4, 10)
        );
    }

    @Test
    void victorFallbackHarmonizationSkipsAdvanceRatioPath() {
        // victor 族水平步进由源逻辑 advance 单元格驱动（墨迹居中 reconcile），
        // advance 比例换算会因解析层 xoffset 解码与主字体视觉校准双重失真，必须走高度比例路径
        assertFalse(TtfBmFontGenerator.useAdvanceHarmonization(true));
        assertTrue(TtfBmFontGenerator.useAdvanceHarmonization(false));
    }

    @Test
    void donatedGlyphMetricsScaleWithLineHeightRatio() {
        // victor14（lineHeight 13）的「中」11×11 advance 12 捐赠给 victor16（lineHeight 14）
        // 应按 14/13 放大到 12×12 advance 13
        assertArrayEquals(
                new int[]{12, 12, 0, 2, 13},
                TtfBmFontGenerator.scaledDonorGlyphMetrics(11, 11, 0, 2, 12, 14.0 / 13.0)
        );
        // 反向缩放（大字号捐赠给小字号）钳非负，偏移可为负
        assertArrayEquals(
                new int[]{7, 7, -1, 1, 8},
                TtfBmFontGenerator.scaledDonorGlyphMetrics(11, 11, -1, 2, 12, 0.65)
        );
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

            final Class<?> sourceClass = Class.forName("github.kasuminova.ssoptimizer.common.font.TtfBmFontGenerator$SourceBmFont");
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
    void parsesEncodedGlyphAdvanceBackIntoLogicalAdvance() throws Exception {
        final Path tempFile = Files.createTempFile("ssoptimizer-font-advance", ".fnt");
        try {
            final String content = "info face=\"test\" size=15 bold=0 italic=0 charset=\"\" unicode=1 smooth=1 aa=1\n"
                    + "common lineHeight=19 base=15 scaleW=256 scaleH=256 pages=1 packed=0\n"
                    + "page id=0 file=\"test_0.png\"\n"
                    + "chars count=1\n"
                    + "char id=65 x=1 y=2 width=3 height=4 xoffset=2 yoffset=1 xadvance=6 page=0 chnl=0\n";
            Files.writeString(tempFile, content, Charset.forName("ISO-8859-1"));

            final Class<?> sourceClass = Class.forName("github.kasuminova.ssoptimizer.common.font.TtfBmFontGenerator$SourceBmFont");
            final Method parse = sourceClass.getDeclaredMethod("parse", Path.class);
            parse.setAccessible(true);
            final Object source = parse.invoke(null, tempFile);

            final Method glyphMetricsMethod = sourceClass.getDeclaredMethod("glyphMetrics");
            glyphMetricsMethod.setAccessible(true);
            final Object rawGlyphMetrics = glyphMetricsMethod.invoke(source);
            assertInstanceOf(java.util.Map.class, rawGlyphMetrics);
            final Object metric = ((java.util.Map<?, ?>) rawGlyphMetrics).get(65);
            assertNotNull(metric);

            final Method xAdvanceMethod = metric.getClass().getDeclaredMethod("xAdvance");
            xAdvanceMethod.setAccessible(true);
            assertEquals(8, xAdvanceMethod.invoke(metric));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void clampsFallbackVisualScaleFactorIntoSafeRange() {
        assertEquals(1.0f, TtfBmFontGenerator.fallbackVisualScaleFactor(12f, 0f));
        assertEquals(1.0f, TtfBmFontGenerator.fallbackVisualScaleFactor(Float.NaN, 10f));
        assertEquals(1.36f, TtfBmFontGenerator.fallbackVisualScaleFactor(20f, 10f));
        assertEquals(0.70f, TtfBmFontGenerator.fallbackVisualScaleFactor(8f, 20f));

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
    void clampsVictorPrimaryVisualScaleFactorIntoSafeRange() {
        assertEquals(1.0f, TtfBmFontGenerator.victorPrimaryVisualScaleFactor(12f, 0f));
        assertEquals(1.0f, TtfBmFontGenerator.victorPrimaryVisualScaleFactor(Float.NaN, 10f));
        assertEquals(1.24f, TtfBmFontGenerator.victorPrimaryVisualScaleFactor(20f, 10f));
        assertEquals(0.94f, TtfBmFontGenerator.victorPrimaryVisualScaleFactor(8f, 20f));

        final float balanced = TtfBmFontGenerator.victorPrimaryVisualScaleFactor(11f, 10f);
        assertTrue(balanced > 1.0f && balanced < 1.24f);
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