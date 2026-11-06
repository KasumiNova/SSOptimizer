package github.kasuminova.ssoptimizer.common.font;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NativeStrokedRasterizerIT {
    private static final float PIXEL_SIZE = 24.0f;
    private static final int   BASELINE   = 24;
    private static final float STROKE_PX  = 2.0f;

    private static Path fontPath;
    private static long faceHandle;

    static {
        final Path rootDir = resolveProjectRoot();
        if (rootDir != null) {
            final Path nativeLib = rootDir.resolve(
                    "modules/internal/sso-font/native/build/lib/main/release/libssoptimizer_font.so");
            if (Files.isRegularFile(nativeLib) && System.getProperty("ssoptimizer.native.path.font") == null) {
                System.setProperty("ssoptimizer.native.path.font", nativeLib.toAbsolutePath().toString());
            }
            fontPath = rootDir.resolve("game-fonts/ttf/MiSans-Regular.ttf");
        }
    }

    private static Path resolveProjectRoot() {
        final String configured = System.getProperty("project.rootDir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath();
        }
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("game-fonts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    @BeforeAll
    static void createFace() {
        Assumptions.assumeTrue(fontPath != null && Files.isRegularFile(fontPath),
                "测试字体 game-fonts/ttf/MiSans-Regular.ttf 不存在");
        assertTrue(NativeFontRasterizer.isAvailable(),
                "native font 栅格化后端必须可用；nativeIT 已依赖 libssoptimizer_font.so release 产物构建");

        faceHandle = NativeFontRasterizer.createFace(fontPath, PIXEL_SIZE, true);
        assertTrue(faceHandle != 0L,
                "createFace 失败，无法创建 native 字体句柄: " + fontPath + ", pixelSize=" + PIXEL_SIZE);
    }

    @AfterAll
    static void destroyFace() {
        if (faceHandle != 0L) {
            NativeFontRasterizer.destroyFace(faceHandle);
            faceHandle = 0L;
        }
    }

    @Test
    void strokedGlyphExpandsFillSilhouette() {
        final NativeGlyphBitmap fill = NativeFontRasterizer.rasterizeGlyph(faceHandle, 'A', BASELINE);
        assertNotNull(fill, "纯填充栅格化 'A' 失败");
        assertTrue(fill.width() > 0 && fill.height() > 0);

        final NativeGlyphBitmap stroked =
                NativeFontRasterizer.rasterizeGlyphStroked(faceHandle, 'A', BASELINE, STROKE_PX);
        assertNotNull(stroked, "描边剪影栅格化 'A' 失败");

        assertTrue(stroked.width() > fill.width(),
                "描边宽度(" + stroked.width() + ")应大于填充宽度(" + fill.width() + ")");
        assertTrue(stroked.height() > fill.height(),
                "描边高度(" + stroked.height() + ")应大于填充高度(" + fill.height() + ")");

        assertEquals(fill.xAdvance(), stroked.xAdvance(), "描边不应改变 xAdvance");

        boolean hasIntermediateAlpha = false;
        for (final int argb : stroked.argbPixels()) {
            final int alpha = (argb >>> 24) & 0xFF;
            if (alpha != 0 && alpha != 255) {
                hasIntermediateAlpha = true;
                break;
            }
        }
        assertTrue(hasIntermediateAlpha, "描边位图边缘缺少抗锯齿 alpha 中间值");
    }

    @Test
    void strokedWithZeroWidthDegradesToFill() {
        final NativeGlyphBitmap fill = NativeFontRasterizer.rasterizeGlyph(faceHandle, 'A', BASELINE);
        final NativeGlyphBitmap degraded =
                NativeFontRasterizer.rasterizeGlyphStroked(faceHandle, 'A', BASELINE, 0.0f);
        assertNotNull(fill);
        assertNotNull(degraded, "strokeWidthPx=0 应退化为纯填充而非失败");
        assertEquals(fill.width(), degraded.width());
        assertEquals(fill.height(), degraded.height());
        assertEquals(fill.xOffset(), degraded.xOffset());
        assertEquals(fill.yOffset(), degraded.yOffset());
        assertEquals(fill.xAdvance(), degraded.xAdvance());
    }

    @Test
    void batchRasterizeMatchesLengthAndKeepsCommonGlyphs() {
        final int[] codePoints = {'A', '汉', 0x10FFFF};
        final NativeGlyphBitmap[] results =
                NativeFontRasterizer.rasterizeGlyphs(faceHandle, codePoints, BASELINE, 0.0f);
        assertNotNull(results, "批量栅格化整体失败");
        assertEquals(codePoints.length, results.length, "返回数组长度必须与入参一致");

        assertNotNull(results[0], "'A' 栅格化失败");
        assertTrue(results[0].width() > 0);
        assertNotNull(results[1], "'汉' 栅格化失败");
        assertTrue(results[1].width() > 0);
    }

    @Test
    void batchStrokedMatchesSingleStrokedSemantics() {
        final NativeGlyphBitmap single =
                NativeFontRasterizer.rasterizeGlyphStroked(faceHandle, '汉', BASELINE, STROKE_PX);
        final NativeGlyphBitmap[] batch =
                NativeFontRasterizer.rasterizeGlyphs(faceHandle, new int[]{'汉'}, BASELINE, STROKE_PX);
        assertNotNull(single);
        assertNotNull(batch);
        assertEquals(1, batch.length);
        assertNotNull(batch[0], "批量描边剪影栅格化 '汉' 失败");
        assertEquals(single.width(), batch[0].width());
        assertEquals(single.height(), batch[0].height());
        assertEquals(single.xAdvance(), batch[0].xAdvance());

        final NativeGlyphBitmap fill = NativeFontRasterizer.rasterizeGlyph(faceHandle, '汉', BASELINE);
        assertNotNull(fill);
        assertTrue(batch[0].width() > fill.width());
        assertTrue(batch[0].height() > fill.height());
    }

    @Test
    void invalidArgumentsReturnNull() {
        assertNull(NativeFontRasterizer.rasterizeGlyphStroked(0L, 'A', BASELINE, STROKE_PX));
        assertNull(NativeFontRasterizer.rasterizeGlyphs(0L, new int[]{'A'}, BASELINE, 0.0f));
        assertNull(NativeFontRasterizer.rasterizeGlyphs(faceHandle, null, BASELINE, 0.0f));
    }
}
