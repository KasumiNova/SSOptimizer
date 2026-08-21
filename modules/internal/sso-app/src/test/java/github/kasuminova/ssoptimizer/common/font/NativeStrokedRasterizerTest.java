package github.kasuminova.ssoptimizer.common.font;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NativeFontRasterizer 描边剪影与批量栅格化的行为验证。
 * <p>
 * 门控：native font 库不可用（未构建或加载失败）时整组测试经 Assumptions 跳过。
 * 库路径通过系统属性 {@code ssoptimizer.native.path.font} 指向
 * {@code modules/internal/sso-font/native/build/lib/main/release/libssoptimizer_font.so}
 * （{@link github.kasuminova.ssoptimizer.common.render.runtime.NativeLibraryResolver}
 * 的按模块覆盖入口），须在首次 isAvailable() 探测前注入，因此在静态块中设置。
 */
class NativeStrokedRasterizerTest {
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
            fontPath = rootDir.resolve("game-fonts/ttf/MiSans-Medium.ttf");
        }
    }

    private static Path resolveProjectRoot() {
        final String configured = System.getProperty("project.rootDir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath();
        }
        // 非 Gradle 启动（如 IDE 直跑）：从工作目录向上找带 game-fonts 的仓库根
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
                "测试字体 game-fonts/ttf/MiSans-Medium.ttf 不存在");
        Assumptions.assumeTrue(NativeFontRasterizer.isAvailable(),
                "native font 栅格化后端不可用（未构建 libssoptimizer_font.so 或 FreeType 缺失）");

        faceHandle = NativeFontRasterizer.createFace(fontPath, PIXEL_SIZE, true);
        Assumptions.assumeTrue(faceHandle != 0L, "createFace 失败");
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

        // 描边向外扩张 STROKE_PX，包围盒必须大于纯填充
        assertTrue(stroked.width() > fill.width(),
                "描边宽度(" + stroked.width() + ")应大于填充宽度(" + fill.width() + ")");
        assertTrue(stroked.height() > fill.height(),
                "描边高度(" + stroked.height() + ")应大于填充高度(" + fill.height() + ")");

        // 描边不改变步进
        assertEquals(fill.xAdvance(), stroked.xAdvance(), "描边不应改变 xAdvance");

        // AA 合成位图边缘应存在非 0/255 的 alpha 中间值
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
        // 0x10FFFF 无对应字形：允许 null（FT_Load_Char 失败）或 .notdef 空位图，不断言内容
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

        // 批量描边结果同样应大于纯填充
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
