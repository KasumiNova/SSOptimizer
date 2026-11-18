package github.kasuminova.ssoptimizer.common.font;

import com.fs.graphics.font.BitmapFont;
import com.fs.graphics.font.BitmapGlyph;
import github.kasuminova.ssoptimizer.bridge.opengl.GL11;
import github.kasuminova.ssoptimizer.common.font.atlas.DynamicGlyphAtlas;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphMetrics;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphQuad;
import github.kasuminova.ssoptimizer.common.font.layout.TextLayoutEngine;
import github.kasuminova.ssoptimizer.common.font.layout.TextPass;
import github.kasuminova.ssoptimizer.common.font.layout.TextRenderState;
import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderFrame;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TtfGlyphProviderTest {

    private static final String VICTOR_PATH   = "graphics/fonts/victor10.fnt";
    private static final String INSIGNIA_PATH = "graphics/fonts/insignia15LTaa.fnt";

    private static final class StubRenderQueue implements RenderQueue {
        final List<GlCommand> submitted = new ArrayList<>();
        final AtomicInteger nextTextureId = new AtomicInteger(100);
        private final RenderFrame frame = new RenderFrame();

        @Override
        public RenderFrame currentFrame() {
            return frame;
        }

        @Override
        public void submit(final GlCommand command) {
            submitted.add(command);
            frame.add(command);
        }

        @Override
        public void swapFrames() {
        }

        @Override
        public void swapFramesAndSync() {
        }

        @Override
        public <T> T get(final Callable<T> getter) {
            throw new UnsupportedOperationException("桩队列不支持 get");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getUncounted(final Callable<T> getter) {
            return (T) Integer.valueOf(nextTextureId.getAndIncrement());
        }

        @Override
        public void wait(final Runnable task) {
        }

        @Override
        public boolean isRenderThread() {
            return false;
        }
    }

    private static final class FakeBackend implements TtfGlyphProvider.TtfRasterBackend {
        final List<int[]>   rasterizeCalls = new ArrayList<>();
        final List<Long>    rasterizeFaces = new ArrayList<>();
        final AtomicInteger createFaceCalls = new AtomicInteger();
        final Map<Long, String> faceFiles = new HashMap<>();
        final java.util.Set<Integer> missingOnPrimary = new java.util.HashSet<>();
        final Map<Integer, Float> scriptedAdvances = new HashMap<>();
        NativeGlyphBitmap forcedInk;
        float lastPixelSize;
        final List<Float> facePixelSizes = new ArrayList<>();

        @Override
        public long createFace(final Path fontFile, final float pixelSize) {
            lastPixelSize = pixelSize;
            facePixelSizes.add(pixelSize);
            final long handle = createFaceCalls.incrementAndGet();
            faceFiles.put(handle, fontFile.getFileName().toString());
            return handle;
        }

        long handleFor(final String fileName) {
            long found = -1L;
            for (final Map.Entry<Long, String> entry : faceFiles.entrySet()) {
                if (entry.getValue().equals(fileName)) {
                    found = Math.max(found, entry.getKey());
                }
            }
            return found;
        }

        @Override
        public boolean hasGlyph(final long face, final int codePoint) {
            return !("lte50549.ttf".equals(faceFiles.get(face)) && missingOnPrimary.contains(codePoint));
        }

        @Override
        public float probeAdvance(final long face, final int codePoint) {
            return scriptedAdvances.getOrDefault(codePoint, 0f);
        }

        @Override
        public NativeGlyphBitmap rasterize(final long face, final int codePoint,
                                           final int baseline, final float strokeWidthPx) {
            rasterizeCalls.add(new int[]{codePoint, baseline, Float.floatToIntBits(strokeWidthPx)});
            rasterizeFaces.add(face);
            if (forcedInk != null) {
                return forcedInk;
            }
            final int[] pixels = new int[16];
            Arrays.fill(pixels, 0xFFFFFFFF);
            return new NativeGlyphBitmap(4, 4, pixels, 2, baseline - 2, 0);
        }

        @Override
        public NativeGlyphBitmap[] rasterizeBatch(final long face, final int[] codePoints,
                                                  final int baseline, final float strokeWidthPx) {
            final NativeGlyphBitmap[] result = new NativeGlyphBitmap[codePoints.length];
            for (int i = 0; i < codePoints.length; i++) {
                result[i] = rasterize(face, codePoints[i], baseline, strokeWidthPx);
            }
            return result;
        }

        @Override
        public void destroyFace(final long face) {
        }
    }

    @TempDir
    Path fontDir;

    private StubRenderQueue queue;

    @BeforeEach
    void setUp() throws Exception {
        queue = new StubRenderQueue();
        GL11.install(queue);
        System.setProperty(EffectiveScreenScale.OVERRIDE_PROPERTY, "1.0");
        Files.createFile(fontDir.resolve("Oxanium-Medium.ttf"));
        Files.createFile(fontDir.resolve("lte50549.ttf"));
    }

    @AfterEach
    void tearDown() {
        GL11.uninstall();
        System.clearProperty(EffectiveScreenScale.OVERRIDE_PROPERTY);
    }

    private static BitmapGlyph glyph(final int id, final int width, final int height,
                                     final int xOffset, final int bearingY, final int xAdvance) {
        final BitmapGlyph glyph = new BitmapGlyph();
        glyph.setGlyphId(id);
        glyph.setWidth(width);
        glyph.setHeight(height);
        glyph.setXOffset(xOffset);
        glyph.setBearingY(bearingY);
        glyph.setXAdvance(xAdvance);
        return glyph;
    }

    private static BitmapFont fixtureFont(final String path) {
        final BitmapFont font = new BitmapFont(path);
        font.setScaleWidth(256);
        font.setScaleHeight(256);
        font.setNominalFontSize(15);
        font.setLineHeight(18);
        font.setBase(13);
        font.addGlyph(glyph(32, 0, 0, 0, 0, 4));
        font.addGlyph(glyph(65, 10, 12, 1, 11, 12));
        font.addGlyph(glyph(97, 8, 9, 1, 9, 9));
        font.addGlyph(glyph(66, 9, 12, 0, 11, 11));
        font.addGlyph(glyph(123, 0, 0, 0, 0, 4));
        font.addGlyph(glyph(200, 1, 1, 0, 0, 5));
        return font;
    }

    private TtfGlyphProvider provider(final String fontPath, final FakeBackend backend) {
        return new TtfGlyphProvider(
                fixtureFont(fontPath),
                OriginalGameFontOverrides.specForPath(fontPath),
                fontDir,
                new DynamicGlyphAtlas(64, 16),
                backend);
    }

    @Test
    void victorLowercaseRasterizesUppercaseButKeepsLowercaseMetrics() {
        final FakeBackend backend = new FakeBackend();
        final TtfGlyphProvider provider = provider(VICTOR_PATH, backend);

        final GlyphMetrics a = provider.glyph('a');

        assertNotNull(a);
        assertEquals(9, a.xAdvance(), "度量取 fnt 'a' 条目（原版布局语义）");
        assertEquals(8, a.width());
        assertEquals(1, backend.rasterizeCalls.size());
        assertEquals(65, backend.rasterizeCalls.get(0)[0], "victor 族小写按大写栅格化");
        assertTrue(a.texWidth() > 0f, "UV 来自图集槽位");
    }

    @Test
    void insigniaDoesNotSubstituteLowercase() {
        final FakeBackend backend = new FakeBackend();
        final TtfGlyphProvider provider = provider(INSIGNIA_PATH, backend);

        provider.glyph('a');

        assertEquals(97, backend.rasterizeCalls.get(0)[0], "非 victor 族不做大小写替换");
    }

    @Test
    void braceIsSpaceEquivalentWithoutAtlasRequest() {
        final FakeBackend backend = new FakeBackend();
        final TtfGlyphProvider provider = provider(INSIGNIA_PATH, backend);

        final GlyphMetrics brace = provider.glyph('{');

        assertNotNull(brace);
        assertEquals(0, brace.width());
        assertEquals(0, brace.height());
        assertEquals(4, brace.xAdvance(), "advance 继承空格");
        assertEquals(0, brace.textureId());
        assertTrue(backend.rasterizeCalls.isEmpty(), "'{' 不产图集请求");
    }

    @Test
    void oneByOnePlaceholderKeepsAdvanceWithoutAtlasRequest() {
        final FakeBackend backend = new FakeBackend();
        final TtfGlyphProvider provider = provider(INSIGNIA_PATH, backend);

        final GlyphMetrics placeholder = provider.glyph(200);

        assertNotNull(placeholder);
        assertEquals(5, placeholder.xAdvance(), "1×1 占位符保留原版 advance");
        assertEquals(0, placeholder.width(), "尺寸归零（bake 期空位图等价，不产可见像素）");
        assertTrue(backend.rasterizeCalls.isEmpty());
    }

    @Test
    void missingBitmapEntryRequiresCoverageFromAnActualFace() throws Exception {
        final FakeBackend backend = new FakeBackend();
        backend.missingOnPrimary.add(0x10FFFF);
        final TtfGlyphProvider primaryOnly = provider(INSIGNIA_PATH, backend);
        assertNull(primaryOnly.glyph(0x10FFFF));
        assertNull(primaryOnly.strokedGlyph(0x10FFFF, 1f));

        Files.createFile(fontDir.resolve("MiSans-Regular.ttf"));
        backend.missingOnPrimary.add((int) '龘');
        backend.forcedInk = new NativeGlyphBitmap(3, 2, new int[]{-1, 0, -1, -1, -1, -1}, 1, 3, 15);
        final TtfGlyphProvider withCjk = provider(INSIGNIA_PATH, backend);
        final BitmapGlyph measured = withCjk.missingGlyphMetrics('龘');
        final GlyphMetrics fill = withCjk.glyph('龘');
        final GlyphMetrics stroke = withCjk.strokedGlyph('龘', 1f);
        assertEquals(15, measured.getXOffset() + measured.getXAdvance());
        assertEquals(15f, fill.xAdvance() + fill.xOffset());
        assertEquals(15f, stroke.xAdvance() + stroke.xOffset());
        assertTrue(fill.texWidth() > 0 && stroke.texWidth() > 0);
        assertEquals(backend.handleFor("MiSans-Regular.ttf"), backend.rasterizeFaces.getLast().longValue());
    }

    @Test
    void glyphFallsBackToFallbackFaceWhenPrimaryLacksGlyph() throws Exception {
        Files.createFile(fontDir.resolve("MiSans-Regular.ttf"));
        final FakeBackend backend = new FakeBackend();
        backend.missingOnPrimary.add(0x8FDC);
        final BitmapFont font = fixtureFont(INSIGNIA_PATH);
        font.addGlyph(glyph(0x8FDC, 12, 12, 0, 12, 13));
        final TtfGlyphProvider provider = new TtfGlyphProvider(
                font, OriginalGameFontOverrides.specForPath(INSIGNIA_PATH), fontDir,
                new DynamicGlyphAtlas(64, 16), backend);

        final GlyphMetrics gm = provider.glyph(0x8FDC);

        assertNotNull(gm, "fallback face 有该字形，不得回退 null");
        assertEquals(5, backend.createFaceCalls.get(),
                "主探针 + 主 face + fallback 探针 + fallback 公式主探针 + fallback face");
        assertEquals(1, backend.rasterizeCalls.size());
        assertEquals(backend.handleFor("MiSans-Regular.ttf"), (long) backend.rasterizeFaces.get(0),
                "栅格化落在 fallback face（链上第二个文件）");
        assertEquals(13, gm.xAdvance(), "度量仍取 fnt");
        assertTrue(gm.textureId() != 0, "槽位纹理已创建");
    }

    @Test
    void glyphReturnsNullWhenNoFaceInChainHasGlyph() {
        final FakeBackend backend = new FakeBackend();
        backend.missingOnPrimary.add(0x8FDC);
        final BitmapFont font = fixtureFont(INSIGNIA_PATH);
        font.addGlyph(glyph(0x8FDC, 12, 12, 0, 12, 13));
        final TtfGlyphProvider provider = new TtfGlyphProvider(
                font, OriginalGameFontOverrides.specForPath(INSIGNIA_PATH), fontDir,
                new DynamicGlyphAtlas(64, 16), backend);

        assertNull(provider.glyph(0x8FDC), "链上全无该字形 → null（引擎 '?' 回退语义不变）");
        assertTrue(backend.rasterizeCalls.isEmpty(), "无 face 含字形时不触发栅格化");
    }

    @Test
    void metricsComeFromFntWhileUvComesFromAtlas() {
        final FakeBackend backend = new FakeBackend();
        final TtfGlyphProvider provider = provider(INSIGNIA_PATH, backend);

        final GlyphMetrics a = provider.glyph('A');

        assertEquals(1, a.xOffset());
        assertEquals(12, a.xAdvance());
        assertEquals(11, a.bearingY());
        assertEquals(10, a.width());
        assertEquals(12, a.height());
        assertEquals(10f / 64f, a.texWidth(), 1e-6f);
        assertEquals(12f / 64f, a.texHeight(), 1e-6f);
        assertNotEquals(0, a.textureId(), "槽位纹理在写入时已创建");
        assertEquals(15f, backend.lastPixelSize, 1e-6f, "face pixelSize = 名义字号 × bucketScale");
    }

    @Test
    void overflowingInkExpandsSlotBoxWithoutClipping() {
        final FakeBackend backend = new FakeBackend();
        final int[] pixels = new int[14 * 16];
        Arrays.fill(pixels, 0xFFFFFFFF);
        backend.forcedInk = new NativeGlyphBitmap(14, 16, pixels, -1, 9, 0);
        final TtfGlyphProvider provider = provider(INSIGNIA_PATH, backend);

        final GlyphMetrics a = provider.glyph('A');

        assertNotNull(a);
        assertEquals(-1f, a.xOffset(), 1e-6f, "盒原点 = 并集左缘（墨迹左越界 2px）");
        assertEquals(9f, a.bearingY(), 1e-6f, "盒顶 = 并集顶缘（墨迹上越界 2px）");
        assertEquals(14f, a.width(), 1e-6f);
        assertEquals(16f, a.height(), 1e-6f);
        assertEquals(14f, a.xAdvance(), 1e-6f,
                "xAdvance 补偿左溢出：步进和保持 fnt 原值（1+12=-1+14）");
        assertEquals(14f / 64f, a.texWidth(), 1e-6f, "槽位覆盖并集全宽");
        assertEquals(16f / 64f, a.texHeight(), 1e-6f);
    }

    @Test
    void victorComposeCentersInkWithinAdvanceCell() {
        final BitmapFont font = fixtureFont(VICTOR_PATH);
        final TtfGlyphProvider provider = new TtfGlyphProvider(
                font, OriginalGameFontOverrides.specForPath(VICTOR_PATH), fontDir,
                new DynamicGlyphAtlas(64, 16), new FakeBackend());
        final int[] inkPixels = new int[8 * 6];
        Arrays.fill(inkPixels, 0xFFFFFFFF);
        final NativeGlyphBitmap ink = new NativeGlyphBitmap(8, 6, inkPixels, 0, 9, 0);

        final GlyphMetrics base = new BitmapFontGlyphProvider(font).glyph('A');
        final NativeGlyphBitmap canvas = provider.composeToFontBox(ink, base, 0, 1.0f);

        assertEquals(1, canvas.xOffset(), "画布原点 = fnt 盒左缘（居中落点在其右侧）");
        assertEquals(10, canvas.width(), "画布宽 = 盒 [1,11)");
        final int[] composed = canvas.argbPixels();
        assertEquals(0, composed[0], "居中后墨迹左缘前留空");
        assertEquals(0, composed[1], "居中后墨迹左缘前留空");
        assertEquals(0xFFFFFFFF, composed[2], "墨迹起始列 = 居中落点");
        assertEquals(0xFFFFFFFF, composed[9], "墨迹结束列");
    }

    @Test
    void nonVictorComposeKeepsNativeBearing() {
        assertEquals(0, TtfGlyphProvider.centeredInkLeftPx(0, 0), "零宽安全");
        assertEquals(3, TtfGlyphProvider.centeredInkLeftPx(13, 8));
        assertEquals(2, TtfGlyphProvider.centeredInkLeftPx(5, 2));
        assertEquals(-1, TtfGlyphProvider.centeredInkLeftPx(10, 12), "墨迹宽于单元格对称负溢出");
    }

    @Test
    void composeCanvasContainsFullInkExtentPixelExact() {
        final BitmapFont font = fixtureFont(INSIGNIA_PATH);
        final TtfGlyphProvider provider = new TtfGlyphProvider(
                font, OriginalGameFontOverrides.specForPath(INSIGNIA_PATH), fontDir,
                new DynamicGlyphAtlas(64, 16), new FakeBackend());
        final int[] inkPixels = new int[6 * 5];
        for (int i = 0; i < inkPixels.length; i++) {
            inkPixels[i] = ((i * 37 + 11) & 0xFF) << 24;
        }
        final NativeGlyphBitmap ink = new NativeGlyphBitmap(6, 5, inkPixels, 0, 8, 0);

        final GlyphMetrics base = new BitmapFontGlyphProvider(font).glyph('a');
        final NativeGlyphBitmap canvas = provider.composeToFontBox(ink, base, 0, 1.0f);

        assertEquals(9, canvas.width(), "并集画布宽");
        assertEquals(10, canvas.height(), "并集画布高");
        assertEquals(0, canvas.xOffset(), "画布原点 = 并集左缘");
        assertEquals(8, canvas.yOffset(), "画布原点 = 并集顶缘");
        final int[] composed = canvas.argbPixels();
        int mismatch = -1;
        for (int row = 0; row < ink.height() && mismatch < 0; row++) {
            for (int col = 0; col < ink.width(); col++) {
                if (composed[row * canvas.width() + col] != inkPixels[row * ink.width() + col]) {
                    mismatch = row * ink.width() + col;
                    break;
                }
            }
        }
        assertTrue(mismatch < 0, "墨迹必须逐像素完整落入画布（无裁剪），首个差异=" + mismatch);
    }

    @Test
    void strokedGlyphExpandsCanvasAndQuantizesStrokeToDeviceHalfPixel() {
        final FakeBackend backend = new FakeBackend();
        final TtfGlyphProvider provider = provider(INSIGNIA_PATH, backend);
        provider.glyph('A');
        provider.forScale(1.0f);

        final GlyphMetrics stroked = provider.strokedGlyph('A', 1.0f);

        assertNotNull(stroked);
        assertEquals(12f / 64f, stroked.texWidth(), 1e-6f);
        assertEquals(14f / 64f, stroked.texHeight(), 1e-6f);
        assertEquals(0f, stroked.xOffset(), 1e-6f, "剪影盒原点 = 盒左外扩 1px");
        assertEquals(13f, stroked.xAdvance(), 1e-6f,
                "xAdvance 补偿左溢出：xOffset+xAdvance 步进和保持 fnt 原值（1+12=0+13）");
        final int[] strokeCall = backend.rasterizeCalls.get(backend.rasterizeCalls.size() - 1);
        assertEquals(1.0f, Float.intBitsToFloat(strokeCall[2]), 1e-6f, "描边宽度按设备像素传给后端");
    }

    @Test
    void forScaleQuantizesWithScreenScaleAndCachesFacePerBucket() {
        final FakeBackend backend = new FakeBackend();
        final TtfGlyphProvider provider = provider(INSIGNIA_PATH, backend);

        provider.forScale(1.0f);
        provider.glyph('A');
        provider.forScale(2.0f);
        provider.glyph('B');
        provider.forScale(2.0f);
        provider.glyph('A');

        assertEquals(3, backend.createFaceCalls.get(),
                "每 bucket 一个 face + 一次校准探针，重复档位复用");
        assertEquals(30f, backend.lastPixelSize, 1e-6f, "bucket 2.0 → pixelSize 30（探针无样本时因子 1.0）");
    }

    private static BitmapFont fixtureWithPrimarySample(final String path, final int sampleAdvance) {
        final BitmapFont font = fixtureFont(path);
        for (final int cp : TtfBmFontGenerator.PRIMARY_ADVANCE_SAMPLE.codePoints().toArray()) {
            font.addGlyph(glyph(cp, 10, 12, 1, 11, sampleAdvance));
        }
        return font;
    }

    @Test
    void calibrationScalesPrimaryFaceToFntAdvanceDensity() {
        final FakeBackend backend = new FakeBackend();
        for (final int cp : TtfBmFontGenerator.PRIMARY_ADVANCE_SAMPLE.codePoints().toArray()) {
            backend.scriptedAdvances.put(cp, 15f);
        }
        final TtfGlyphProvider provider = new TtfGlyphProvider(
                fixtureWithPrimarySample(INSIGNIA_PATH, 12),
                OriginalGameFontOverrides.specForPath(INSIGNIA_PATH), fontDir,
                new DynamicGlyphAtlas(64, 16), backend);

        provider.glyph('A');

        assertEquals(2, backend.facePixelSizes.size(), "校准探针 + 正式 face 各一次创建");
        assertEquals(15f, backend.facePixelSizes.get(0), 1e-6f, "探针 face 用名义字号");
        assertEquals(15f * 0.88f, backend.facePixelSizes.get(1), 1e-4f,
                "正式 face 按钳制后的校准因子缩放（0.8 → 0.88）");
    }

    @Test
    void calibrationSkipsScalingWhenAdvancesAlreadyMatch() {
        final FakeBackend backend = new FakeBackend();
        for (final int cp : TtfBmFontGenerator.PRIMARY_ADVANCE_SAMPLE.codePoints().toArray()) {
            backend.scriptedAdvances.put(cp, 12f);
        }
        final TtfGlyphProvider provider = new TtfGlyphProvider(
                fixtureWithPrimarySample(INSIGNIA_PATH, 12),
                OriginalGameFontOverrides.specForPath(INSIGNIA_PATH), fontDir,
                new DynamicGlyphAtlas(64, 16), backend);

        provider.glyph('A');

        assertEquals(15f, backend.facePixelSizes.get(1), 1e-6f, "步进一致时不缩放");
    }

    @Test
    void calibrationScalesFallbackFaceByCjkSample() throws Exception {
        Files.createFile(fontDir.resolve("MiSans-Regular.ttf"));
        final FakeBackend backend = new FakeBackend();
        backend.missingOnPrimary.add(0x6C49);
        final BitmapFont font = fixtureWithPrimarySample(INSIGNIA_PATH, 12);
        for (final int cp : TtfBmFontGenerator.PRIMARY_ADVANCE_SAMPLE.codePoints().toArray()) {
            backend.scriptedAdvances.put(cp, 12f);
        }
        for (final int cp : TtfBmFontGenerator.FALLBACK_VISUAL_SAMPLE.codePoints().toArray()) {
            font.addGlyph(glyph(cp, 13, 13, 0, 12, 13));
            backend.scriptedAdvances.put(cp, 20f);
        }
        final TtfGlyphProvider provider = new TtfGlyphProvider(
                font, OriginalGameFontOverrides.specForPath(INSIGNIA_PATH), fontDir,
                new DynamicGlyphAtlas(64, 16), backend);

        assertNotNull(provider.glyph(0x6C49), "fallback face 栅格化 '汉'");

        assertEquals(5, backend.facePixelSizes.size(),
                "主探针 + 主 face + fallback 探针 + fallback 公式主探针 + fallback face");
        assertEquals(15f, backend.facePixelSizes.get(2), 1e-6f, "fallback 探针 face 用名义字号");
        assertEquals(15f * 0.70f, backend.facePixelSizes.get(4), 1e-4f,
                "fallback 正式 face 按 CJK 采样校准（0.65 → 钳制 0.70）");
    }

    @Test
    void calibrationFallbackFollowsPrimaryRenderedAdvanceRatio() throws Exception {
        Files.createFile(fontDir.resolve("MiSans-Regular.ttf"));
        final FakeBackend backend = new FakeBackend();
        backend.missingOnPrimary.add(0x6C49);
        final BitmapFont font = fixtureWithPrimarySample(INSIGNIA_PATH, 12);
        for (final int cp : TtfBmFontGenerator.PRIMARY_ADVANCE_SAMPLE.codePoints().toArray()) {
            backend.scriptedAdvances.put(cp, 12f);
        }
        for (final int cp : TtfBmFontGenerator.FALLBACK_VISUAL_SAMPLE.codePoints().toArray()) {
            font.addGlyph(glyph(cp, 13, 13, 0, 12, 13));
            backend.scriptedAdvances.put(cp, 14f);
        }
        final TtfGlyphProvider provider = new TtfGlyphProvider(
                font, OriginalGameFontOverrides.specForPath(INSIGNIA_PATH), fontDir,
                new DynamicGlyphAtlas(64, 16), backend);

        assertNotNull(provider.glyph(0x6C49), "fallback face 栅格化 '汉'");

        assertEquals(15f * (13f / 14f), backend.facePixelSizes.get(4), 1e-4f,
                "fallback 校准因子 = 目标步进 13 / native 步进 14");
    }

    @Test
    void calibrationPassesThroughLegitSmallCjkRatio() throws Exception {
        Files.createFile(fontDir.resolve("MiSans-Regular.ttf"));
        final FakeBackend backend = new FakeBackend();
        backend.missingOnPrimary.add(0x6C49);
        final BitmapFont font = fixtureWithPrimarySample(INSIGNIA_PATH, 12);
        for (final int cp : TtfBmFontGenerator.PRIMARY_ADVANCE_SAMPLE.codePoints().toArray()) {
            backend.scriptedAdvances.put(cp, 12f);
        }
        for (final int cp : TtfBmFontGenerator.FALLBACK_VISUAL_SAMPLE.codePoints().toArray()) {
            font.addGlyph(glyph(cp, 13, 13, 0, 12, 13));
            backend.scriptedAdvances.put(cp, 18f);
        }
        final TtfGlyphProvider provider = new TtfGlyphProvider(
                font, OriginalGameFontOverrides.specForPath(INSIGNIA_PATH), fontDir,
                new DynamicGlyphAtlas(64, 16), backend);

        assertNotNull(provider.glyph(0x6C49), "fallback face 栅格化 '汉'");

        assertEquals(15f * (13f / 18f), backend.facePixelSizes.get(4), 1e-4f,
                "合法小 CJK 比率 0.722 穿透钳制，不被抬到 0.88");
    }

    @Test
    void slotMetricsCacheBypassesAtlasOnRepeatAndInvalidatesOnEviction() {
        final FakeBackend backend = new FakeBackend();
        final DynamicGlyphAtlas atlas = new DynamicGlyphAtlas(64, 1);
        final TtfGlyphProvider provider = new TtfGlyphProvider(
                fixtureFont(INSIGNIA_PATH),
                OriginalGameFontOverrides.specForPath(INSIGNIA_PATH), fontDir, atlas, backend);

        final GlyphMetrics first = provider.glyph('A');
        assertNotNull(first);
        assertTrue(first.textureId() != 0, "桩队列下纹理已创建，结果应入缓存");
        assertSame(first, provider.glyph('A'),
                "同 (bucket, stroke, 码点) 重复查询命中直通缓存，不进 atlas.request");
        final int rasterizeBeforeEviction = backend.rasterizeCalls.size();

        assertNotNull(provider.strokedGlyph('A', 1.0f), "描边组开页触发填充组淘汰");

        final GlyphMetrics third = provider.glyph('A');
        assertNotNull(third);
        assertNotSame(first, third, "整组淘汰（纹理代际递增）后缓存必须失效");
        assertTrue(backend.rasterizeCalls.size() > rasterizeBeforeEviction,
                "淘汰后穿透到图集重新栅格化");
    }

    @Test
    void bitmapAndTtfSourcesProduceIdenticalLayoutGeometry() {
        final BitmapFont font = fixtureFont(INSIGNIA_PATH);
        final BitmapFontGlyphProvider bitmap = new BitmapFontGlyphProvider(font);
        final TtfGlyphProvider ttf = new TtfGlyphProvider(
                font, OriginalGameFontOverrides.specForPath(INSIGNIA_PATH), fontDir,
                new DynamicGlyphAtlas(64, 16), new FakeBackend());

        final TextRenderState state = TextRenderState.builder("ABa")
                .draw(100f, 200f).fontSize(15f).borderEnabled(true).outlineColor(0x000000).build();
        final List<TextPass> bitmapPasses = TextLayoutEngine.layout(state, bitmap);
        final List<TextPass> ttfPasses = TextLayoutEngine.layout(state, ttf);

        final List<GlyphQuad> bitmapFill = bitmapPasses.get(bitmapPasses.size() - 1).quads();
        final List<GlyphQuad> ttfFill = new ArrayList<>();
        for (final GlyphQuad q : ttfPasses.get(ttfPasses.size() - 1).quads()) {
            if (q.textureId() != 0 && q.color() == 0xFFFFFFFF) {
                ttfFill.add(q);
            }
        }
        assertEquals(bitmapFill.size(), ttfFill.size(), "填充 quad 数一致");
        for (int i = 0; i < bitmapFill.size(); i++) {
            final GlyphQuad b = bitmapFill.get(i);
            final GlyphQuad t = ttfFill.get(i);
            assertEquals(b.x1(), t.x1(), 1e-4f, "quad " + i + " 几何一致");
            assertEquals(b.y1(), t.y1(), 1e-4f);
            assertEquals(b.x3(), t.x3(), 1e-4f);
            assertEquals(b.y2(), t.y2(), 1e-4f);
            assertEquals(b.color(), t.color());
        }
    }

    @Test
    void missingFontFileFailsConstructionWithPath() {
        final IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                new TtfGlyphProvider(
                        fixtureFont(INSIGNIA_PATH),
                        OriginalGameFontOverrides.specForPath(INSIGNIA_PATH),
                        fontDir.resolve("nonexistent"),
                        new DynamicGlyphAtlas(64, 16),
                        new FakeBackend()));
        assertTrue(error.getMessage().contains(INSIGNIA_PATH), "异常信息带 spec 路径与候选清单");
    }
}
