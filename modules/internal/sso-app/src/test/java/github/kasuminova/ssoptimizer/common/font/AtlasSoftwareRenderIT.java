package github.kasuminova.ssoptimizer.common.font;

import com.fs.graphics.font.BitmapFont;
import com.fs.graphics.font.BitmapGlyph;
import github.kasuminova.ssoptimizer.bridge.opengl.GL11;
import github.kasuminova.ssoptimizer.common.font.atlas.AtlasTestHooks;
import github.kasuminova.ssoptimizer.common.font.atlas.DynamicGlyphAtlas;
import github.kasuminova.ssoptimizer.common.font.atlas.GlyphAtlasPage;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphMetrics;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphQuad;
import github.kasuminova.ssoptimizer.common.font.layout.TextLayoutEngine;
import github.kasuminova.ssoptimizer.common.font.layout.TextPass;
import github.kasuminova.ssoptimizer.common.font.layout.TextRenderState;
import github.kasuminova.ssoptimizer.common.render.engine.TextScaleBuckets;
import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderFrame;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字体重写 P3（TTF 动态图集）的离线软件渲染隔离验证。
 * <p>
 * 动机：实机 RT 模式下字体被垂直压成 2-4px 横条（x 步进正常），本测试把
 * 「native 栅格化 → composeToFontBox 合成 → 图集 staging → 槽位 UV → 引擎 quad」
 * 全链路在纯软件下复现并逐环断言，产出 PNG 供人工比对，从而把问题隔离在
 * 栅格化/合成/图集/UV 数学层 还是 GL 发射/执行层。
 * <p>
 * 使用真实 native 后端（不经假实现）；native 库不可用或字体文件缺失时整组
 * Assumptions 跳过。PNG 产出到 {@code <root>/build/font-atlas-dump/}（先清空再写）。
 * <p>
 * fixture：直接解析 {@code game-fonts/fnt/insignia15LTaa.fnt}（原版真实 fnt，
 * GBK 元数据行只取数值字段故按 ISO-8859-1 读）装配 BitmapFont；TTF 源为
 * {@code game-fonts/ttf/lte50549.ttf}（original-match profile 的 insignia 族主字体）。
 * 屏幕缩放经 {@code ssoptimizer.font.screenscale.override=1.0} 钉死，
 * bucket 直接经 {@code forScale(bucket)} 指定。
 */
class AtlasSoftwareRenderIT {
    private static final String INSIGNIA_PATH = "graphics/fonts/insignia15LTaa.fnt";
    private static final float[] BUCKETS      = {1.0f, 1.5f, 2.0f};
    private static final int[]   PROBE_GLYPHS = {'A', '汉', 'g', '_'};
    private static final float   STROKE_LOGICAL_PX = 1.0f;
    private static final int     ATLAS_PAGE_SIZE   = 1024;
    private static final String  RENDER_TEXT  = "Starsector 远行星号 123 ABC defg";
    /** 软渲染画布尺寸与落笔点（任务书固定值）。 */
    private static final int     CANVAS_W = 800;
    private static final int     CANVAS_H = 120;
    private static final float   DRAW_X   = 10f;
    private static final float   DRAW_Y   = 60f;
    /** 画布背景：深灰，白字与黑描边剪影都可见。 */
    private static final int     CANVAS_BG = 0xFF303030;

    private static Path   rootDir;
    private static Path   ttfPath;
    private static Path   misansPath;
    private static Path   fntPath;
    private static Path   dumpDir;
    private static BitmapFont fixtureFont;
    private static BitmapFontGlyphProvider fixtureMetrics;
    private static String faceKey;

    /** 跨用例共享的 provider + 图集（glyph() 幂等，各用例自行补齐所需字形）。 */
    private static DynamicGlyphAtlas atlas;
    private static TtfGlyphProvider  provider;

    static {
        rootDir = resolveProjectRoot();
        if (rootDir != null) {
            final Path nativeLib = rootDir.resolve(
                    "modules/internal/sso-font/native/build/lib/main/release/libssoptimizer_font.so");
            if (Files.isRegularFile(nativeLib) && System.getProperty("ssoptimizer.native.path.font") == null) {
                System.setProperty("ssoptimizer.native.path.font", nativeLib.toAbsolutePath().toString());
            }
            ttfPath = rootDir.resolve("game-fonts/ttf/lte50549.ttf");
            misansPath = rootDir.resolve("game-fonts/ttf/MiSans-Regular.ttf");
            fntPath = rootDir.resolve("game-fonts/fnt/insignia15LTaa.fnt");
            dumpDir = rootDir.resolve("build/font-atlas-dump");
        }
    }

    /** 桩队列：allocate 发假纹理 id（不执行 GL 命令体），submit 只记录。 */
    private static final class StubRenderQueue implements RenderQueue {
        final AtomicInteger nextTextureId = new AtomicInteger(100);
        private final RenderFrame frame = new RenderFrame();

        @Override
        public RenderFrame currentFrame() {
            return frame;
        }

        @Override
        public void submit(final GlCommand command) {
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
    static void setUp() throws IOException {
        Assumptions.assumeTrue(rootDir != null, "无法定位项目根目录");
        Assumptions.assumeTrue(Files.isRegularFile(ttfPath), "测试字体不存在: " + ttfPath);
        Assumptions.assumeTrue(Files.isRegularFile(misansPath), "测试字体不存在: " + misansPath);
        Assumptions.assumeTrue(Files.isRegularFile(fntPath), "原版 fnt fixture 不存在: " + fntPath);
        Assumptions.assumeTrue(NativeFontRasterizer.isAvailable(),
                "native font 栅格化后端不可用（未构建 libssoptimizer_font.so）");

        // 清空并重建 PNG 产出目录
        if (Files.isDirectory(dumpDir)) {
            try (var stream = Files.list(dumpDir)) {
                for (final Path file : stream.toList()) {
                    Files.delete(file);
                }
            }
        } else {
            Files.createDirectories(dumpDir);
        }

        System.setProperty(EffectiveScreenScale.OVERRIDE_PROPERTY, "1.0");
        GL11.install(new StubRenderQueue());

        fixtureFont = parseFnt(fntPath, INSIGNIA_PATH);
        fixtureMetrics = new BitmapFontGlyphProvider(fixtureFont);
        faceKey = OriginalGameFontOverrides.specForPath(INSIGNIA_PATH).normalizedOriginalFontPath();
        atlas = new DynamicGlyphAtlas(ATLAS_PAGE_SIZE, 16);
        provider = new TtfGlyphProvider(
                fixtureFont,
                OriginalGameFontOverrides.specForPath(INSIGNIA_PATH),
                rootDir.resolve("game-fonts/ttf"),
                atlas);
        System.out.println("[AtlasIT] PNG 产出目录: " + dumpDir.toAbsolutePath());
    }

    @AfterAll
    static void tearDown() {
        GL11.uninstall();
        System.clearProperty(EffectiveScreenScale.OVERRIDE_PROPERTY);
    }

    // ------------------------------------------------------------------
    // 原版 fnt 解析（数值字段；face 名为 GBK 故整文件按 ISO-8859-1 读）
    // ------------------------------------------------------------------

    private static BitmapFont parseFnt(final Path path, final String resourcePath) throws IOException {
        final BitmapFont font = new BitmapFont(resourcePath);
        int scaleW = 1;
        int scaleH = 1;
        final List<String> lines = Files.readAllLines(path, StandardCharsets.ISO_8859_1);
        for (final String line : lines) {
            if (line.startsWith("info ")) {
                font.setNominalFontSize(Math.abs(intField(line, "size")));
            } else if (line.startsWith("common ")) {
                font.setLineHeight(intField(line, "lineHeight"));
                font.setBase(intField(line, "base"));
                scaleW = intField(line, "scaleW");
                scaleH = intField(line, "scaleH");
                font.setScaleWidth(scaleW);
                font.setScaleHeight(scaleH);
            }
        }
        for (final String line : lines) {
            if (!line.startsWith("char id=")) {
                continue;
            }
            final BitmapGlyph glyph = new BitmapGlyph();
            glyph.setGlyphId(intField(line, "id"));
            final int x = intField(line, "x");
            final int y = intField(line, "y");
            final int width = intField(line, "width");
            final int height = intField(line, "height");
            glyph.setX(x);
            glyph.setWidth(width);
            glyph.setHeight(height);
            glyph.setXOffset(intField(line, "xoffset"));
            // 游戏解析器把 yoffset 原样写入 bearingY（见 BitmapFontManager 字节码）
            glyph.setBearingY(intField(line, "yoffset"));
            glyph.setXAdvance(intField(line, "xadvance"));
            glyph.setPage(intField(line, "page"));
            glyph.setTexX(x / (float) scaleW);
            glyph.setTexY(y / (float) scaleH);
            glyph.setTexWidth(width / (float) scaleW);
            glyph.setTexHeight(height / (float) scaleH);
            font.addGlyph(glyph);
        }
        return font;
    }

    /** 抽取 {@code key=value} 的整数值（value 到下一个空白为止，允许负值）。 */
    private static int intField(final String line, final String key) {
        final int keyStart = line.indexOf(key + "=");
        if (keyStart < 0) {
            throw new IllegalArgumentException("fnt 行缺少字段 " + key + ": " + line);
        }
        int valueStart = keyStart + key.length() + 1;
        if (line.charAt(valueStart) == '"') {
            throw new IllegalArgumentException("字段 " + key + " 非数值: " + line);
        }
        int valueEnd = valueStart;
        while (valueEnd < line.length() && !Character.isWhitespace(line.charAt(valueEnd))) {
            valueEnd++;
        }
        return Integer.parseInt(line.substring(valueStart, valueEnd));
    }

    // ------------------------------------------------------------------
    // 任务 1：单字形合成验证（纯填充 + 描边剪影，bucket ∈ {1.0, 1.5, 2.0}）
    // ------------------------------------------------------------------

    @Test
    void singleGlyphComposeMatchesFontBox() throws IOException {
        for (final float bucket : BUCKETS) {
            final int bucketMillis = Math.round(bucket * 1000);
            // 与生产 face 一致：使用运行期校准后的像素字号，compose PNG 反映真实观感
            final float pixelSize = provider.calibratedPixelSize(ttfPath, bucketMillis);
            final float cjkPixelSize = provider.calibratedPixelSize(misansPath, bucketMillis);
            final int baseline = Math.round(fixtureFont.getBase() * bucket);
            final long latinFace = NativeFontRasterizer.createFace(ttfPath, pixelSize, true);
            assertTrue(latinFace != 0L, "createFace 失败: bucket=" + bucket);
            // lte50549.ttf 只覆盖 Latin（生产 face 链的 CJK 由 MiSans fallback face
            // 栅格化），此处手动分 face 验证合成数学
            final long cjkFace = NativeFontRasterizer.createFace(misansPath, cjkPixelSize, true);
            assertTrue(cjkFace != 0L, "createFace(MiSans) 失败: bucket=" + bucket);
            try {
                for (final int cp : PROBE_GLYPHS) {
                    final long face = cp >= 0x2E80 ? cjkFace : latinFace;
                    final float facePixelSize = cp >= 0x2E80 ? cjkPixelSize : pixelSize;
                    verifyCompose(face, cp, baseline, bucket, facePixelSize, 0f, "fill");
                    verifyCompose(face, cp, baseline, bucket, facePixelSize, STROKE_LOGICAL_PX, "stroke");
                }
            } finally {
                NativeFontRasterizer.destroyFace(latinFace);
                NativeFontRasterizer.destroyFace(cjkFace);
            }
        }
    }

    /** 单字形：native 墨迹 → composeToFontBox → PNG + 度量打印 + 画布尺寸/墨高断言。 */
    private static void verifyCompose(final long face,
                                      final int cp,
                                      final int baseline,
                                      final float bucket,
                                      final float pixelSize,
                                      final float strokeWidthLogicalPx,
                                      final String kind) throws IOException {
        final GlyphMetrics base = fixtureMetrics.glyph(cp);
        assertNotNull(base, "fnt 缺失字形: " + cpLabel(cp));

        // 量化描边设备像素（与 provider 内部同一换算：TextScaleBuckets.quantizeStrokeDevicePx）
        final int strokeBucketMillis = strokeWidthLogicalPx > 0f
                ? Math.round(TextScaleBuckets.quantizeStrokeDevicePx(strokeWidthLogicalPx, bucket) * 1000f)
                : 0;
        final float strokeDevicePx = strokeBucketMillis / 1000f;
        final NativeGlyphBitmap ink = strokeBucketMillis > 0
                ? NativeFontRasterizer.rasterizeGlyphStroked(face, cp, baseline, strokeDevicePx)
                : NativeFontRasterizer.rasterizeGlyph(face, cp, baseline);
        assertNotNull(ink, "native 栅格化失败: " + cpLabel(cp) + " bucket=" + bucket + " kind=" + kind);

        final NativeGlyphBitmap canvas =
                provider.composeToFontBox(ink, base, strokeBucketMillis, bucket);

        final int expand = Math.round(strokeBucketMillis / 1000f);
        // 并集画布期望尺寸 = 「fnt 盒 × bucket + 描边外扩」∪ 墨迹包围盒
        final int boxLeft = Math.round(base.xOffset() * bucket) - expand;
        final int boxTop = Math.round(base.bearingY() * bucket) - expand;
        final int boxRight = boxLeft + Math.max(1, Math.round(base.width() * bucket) + expand * 2);
        final int boxBottom = boxTop + Math.max(1, Math.round(base.height() * bucket) + expand * 2);
        final int expectedLeft = Math.min(boxLeft, ink.xOffset());
        final int expectedTop = Math.min(boxTop, ink.yOffset());
        final int expectedW = Math.max(boxRight, ink.xOffset() + ink.width()) - expectedLeft;
        final int expectedH = Math.max(boxBottom, ink.yOffset() + ink.height()) - expectedTop;
        final int inkRows = inkedRows(canvas.argbPixels(), canvas.width(), canvas.height());

        System.out.printf("[compose] %s %s bucket=%.3f pixelSize=%.1f baseline=%d "
                        + "fntBox=%.0fx%.0f xOffset=%.0f bearingY=%.0f | ink=%dx%d@(%d,%d) | canvas=%dx%d (期望 %dx%d) 墨行=%d%n",
                cpLabel(cp), kind, bucket, pixelSize, baseline,
                base.width(), base.height(), base.xOffset(), base.bearingY(),
                ink.width(), ink.height(), ink.xOffset(), ink.yOffset(),
                canvas.width(), canvas.height(), expectedW, expectedH, inkRows);

        assertEquals(expectedW, canvas.width(),
                cpLabel(cp) + " " + kind + " 画布宽必须等于「缩放 fnt 盒 ∪ 墨迹」并集宽");
        assertEquals(expectedH, canvas.height(),
                cpLabel(cp) + " " + kind + " 画布高必须等于「缩放 fnt 盒 ∪ 墨迹」并集高");
        assertEquals(expectedLeft, canvas.xOffset(), cpLabel(cp) + " 画布原点 = 并集左缘");
        assertEquals(expectedTop, canvas.yOffset(), cpLabel(cp) + " 画布原点 = 并集顶缘");

        // 零裁剪回归断言（bucket 1.5 CJK 右缘/顶缘裁切 bug 的护栏）：
        // 墨迹逐像素落在画布 (ink 偏移 − 画布原点) 处，逐字节一致
        final int[] canvasPixels = canvas.argbPixels();
        final int[] inkPixels = ink.argbPixels();
        final int destX = ink.xOffset() - canvas.xOffset();
        final int destY = ink.yOffset() - canvas.yOffset();
        int clip = -1;
        for (int row = 0; row < ink.height() && clip < 0; row++) {
            for (int col = 0; col < ink.width(); col++) {
                if (canvasPixels[(destY + row) * canvas.width() + destX + col]
                        != inkPixels[row * ink.width() + col]) {
                    clip = row * ink.width() + col;
                    break;
                }
            }
        }
        assertTrue(clip < 0,
                cpLabel(cp) + " " + kind + " 墨迹必须零裁剪落入画布，首个差异像素=" + clip);
        // 压扁症状探针：除 '_'（天生薄条，fnt 高 4px）外，墨迹应覆盖画布高度的大部分
        if (cp != '_' && strokeWidthLogicalPx == 0f) {
            assertTrue(inkRows >= canvas.height() * 0.5,
                    cpLabel(cp) + " 墨迹行数(" + inkRows + ")不足画布高(" + canvas.height() + ")一半，疑似垂直压扁");
        }

        writePng(String.format("compose_%s_U+%04X_bucket%.3f.png", kind, cp, bucket),
                alphaToGray(canvas.argbPixels()), canvas.width(), canvas.height(), 4);
    }

    // ------------------------------------------------------------------
    // 任务 2：图集页 staging 验证（ASCII 可打印 + 常用 CJK 200 字）
    // ------------------------------------------------------------------

    @Test
    void atlasPageStagingDump() throws IOException {
        provider.forScale(1.0f);
        final List<Integer> requested = new ArrayList<>();
        for (int cp = 32; cp <= 126; cp++) {
            requested.add(cp);
        }
        requested.addAll(commonCjkCodePoints(200));

        int expectedSlots = 0;
        for (final int cp : requested) {
            final GlyphMetrics gm = provider.glyph(cp);
            if (gm != null && gm.width() > 0 && gm.height() > 0) {
                expectedSlots++;
            }
        }

        final int slotCount = atlas.slotCount(faceKey, 1000, 0);
        System.out.println("[atlas] 请求字形=" + requested.size() + " 入槽=" + slotCount
                + " 页数(bucket1.0,填充)=" + AtlasTestHooks.pagesOfGroup(atlas, faceKey, 1000, 0).size());
        // 共享图集可能被其他用例（阴影/设备像素复现等）提前向同组写入额外字形，
        // 故断言「不少于」而非「等于」：本用例请求的字形必须全部入槽
        assertTrue(slotCount >= expectedSlots,
                "glyph() 成功的字形必须全部入槽: 成功=" + expectedSlots + " 入槽=" + slotCount);
        assertTrue(slotCount > 200, "ASCII + CJK 200 应产出 200+ 槽位");

        final List<GlyphAtlasPage> pages = AtlasTestHooks.pagesOfGroup(atlas, faceKey, 1000, 0);
        assertFalse(pages.isEmpty(), "填充组至少一页");
        for (int i = 0; i < pages.size(); i++) {
            final byte[] staging = AtlasTestHooks.stagingSnapshot(pages.get(i));
            assertEquals(ATLAS_PAGE_SIZE * ATLAS_PAGE_SIZE, staging.length);
            // staging 行序直接映射 GL 纹理行序（行 0 = v 0 = 字形底），
            // dump 时垂直翻转回 PNG 阅读约定（行 0 = 字形顶）
            writePng("atlas_fill_page" + i + ".png", alphaBytesToGray(flipStagingVertically(staging)),
                    ATLAS_PAGE_SIZE, ATLAS_PAGE_SIZE, 1);
            final int nonZero = countNonZero(staging);
            System.out.println("[atlas] fill page" + i + " 非零像素=" + nonZero);
            assertTrue(nonZero > 0, "图集页 staging 不应为空");
        }
    }

    /** 常用 CJK 码点：取 fixture fnt 中 0x4E00..0x9FFF 区间的前 N 个。 */
    private static List<Integer> commonCjkCodePoints(final int count) {
        final List<Integer> codePoints = new ArrayList<>();
        final BitmapGlyph[] glyphs = fixtureFont.getGlyphs();
        for (int cp = 0x4E00; cp <= 0x9FFF && codePoints.size() < count; cp++) {
            if (cp < glyphs.length && glyphs[cp] != null
                    && glyphs[cp].getWidth() > 0 && glyphs[cp].getHeight() > 0) {
                codePoints.add(cp);
            }
        }
        return codePoints;
    }

    // ------------------------------------------------------------------
    // 任务 3：端到端软渲染（引擎 quad 流 → staging 采样 → alpha 混合）
    // ------------------------------------------------------------------

    @Test
    void softwareRenderedTextIsReadable() throws IOException {
        provider.forScale(1.0f);
        final TextRenderState state = TextRenderState.builder(RENDER_TEXT)
                .draw(DRAW_X, DRAW_Y)
                .fontSize(15f)
                .textColor(0xFFFFFF, 1f)
                .outlineColor(0x000000)
                .borderAlpha(1f)
                .borderEnabled(true)
                .build();
        final List<TextPass> passes = TextLayoutEngine.layout(state, provider);
        assertFalse(passes.isEmpty());

        // 纹理 id → staging 快照（填充组 + 描边组；stub 队列在写页时已发假 id）
        final Map<Integer, byte[]> textures = new HashMap<>();
        for (final GlyphAtlasPage page : AtlasTestHooks.pagesOfGroup(atlas, faceKey, 1000, 0)) {
            textures.put(page.textureId(), AtlasTestHooks.stagingSnapshot(page));
        }
        for (final GlyphAtlasPage page : AtlasTestHooks.pagesOfGroup(atlas, faceKey, 1000, 1000)) {
            textures.put(page.textureId(), AtlasTestHooks.stagingSnapshot(page));
        }

        final int[] canvas = new int[CANVAS_W * CANVAS_H];
        java.util.Arrays.fill(canvas, CANVAS_BG);
        int quadCount = 0;
        int skippedQuads = 0;
        for (final TextPass pass : passes) {
            for (final GlyphQuad quad : pass.quads()) {
                final byte[] staging = textures.get(quad.textureId());
                if (staging == null) {
                    skippedQuads++;
                    continue;
                }
                drawQuad(canvas, quad, staging);
                quadCount++;
            }
        }
        System.out.println("[softrender] passes=" + passes.size() + " quads=" + quadCount
                + " 跳过(无纹理)=" + skippedQuads);
        assertTrue(quadCount > 0);
        assertEquals(0, skippedQuads, "所有 quad 的 textureId 都必须能解析到图集页");

        writePng("softrender_ttf.png", canvas, CANVAS_W, CANVAS_H, 1);

        // 压扁症状探针：整行墨高跨度（白填充 + 黑剪影任一着色即算墨）
        final int[] span = inkedRowSpan(canvas);
        System.out.println("[softrender] 墨行区间=[" + span[0] + "," + span[1] + "] 跨度=" + (span[1] - span[0] + 1));
        assertTrue(span[1] - span[0] + 1 >= 12,
                "15px 字整行墨高跨度应 ≥12px（压扁症状为 2-4px），实际=" + (span[1] - span[0] + 1));

        // 逐字形探针：fnt 高 ≥10 的填充 quad，其区域内的墨行数不得 ≤4（压扁特征）。
        // face 链已接入（lte50549 无 CJK → MiSans fallback face 栅格化），
        // 入槽 quad 的槽位区域必须全部有墨，空白槽位计数恒为 0
        int tallGlyphs = 0;
        int blankSlotGlyphs = 0;
        for (final TextPass pass : passes) {
            for (final GlyphQuad quad : pass.quads()) {
                if (quad.color() != 0xFFFFFFFF) {
                    continue; // 只看白填充 quad
                }
                final float quadHeight = quad.y1() - quad.y2();
                if (quadHeight < 10f) {
                    continue;
                }
                final byte[] staging = textures.get(quad.textureId());
                if (staging == null || !slotRegionHasInk(staging, quad)) {
                    blankSlotGlyphs++;
                    continue;
                }
                tallGlyphs++;
                final int rows = quadInkedRows(canvas, quad);
                assertTrue(rows > 4,
                        "填充 quad 高=" + quadHeight + " 但区域内墨行=" + rows + "（≤4 即压扁症状）");
            }
        }
        System.out.println("[softrender] 高字形(≥10px)填充 quad 数=" + tallGlyphs
                + " 空白槽位跳过=" + blankSlotGlyphs);
        assertTrue(tallGlyphs >= 10, "字符串中应有 10+ 个高字形");
        assertEquals(0, blankSlotGlyphs,
                "face 链接入后所有入槽 quad 的槽位区域必须有墨（CJK 由 MiSans fallback face 栅格化）");

        // 位图源对照：fixture 无 png 页（game-fonts/fnt 只有 .fnt），按任务书跳过
        final Path bitmapPage = fntPath.resolveSibling("insignia15LTaa_0.png");
        if (Files.isRegularFile(bitmapPage)) {
            renderBitmapControl(bitmapPage);
        } else {
            System.out.println("[softrender] fixture 无 png 页（" + bitmapPage.getFileName() + "），跳过位图源对照");
        }
    }

    // ------------------------------------------------------------------
    // 任务 3c：阴影 pass 软渲染复现（bucket 1.0 / 1.5，默认偏移 (1,-1)）
    // ------------------------------------------------------------------

    /**
     * 阴影观感复现：同一文本在两个 bucket 下渲染「阴影 pass + 主 pass」，
     * dump 2× 放大 PNG 供目检「阴影逐字形不一致 / 毛边」症状。
     */
    @Test
    void shadowPassSoftRender() throws IOException {
        for (final float bucket : new float[]{1.0f, 1.5f}) {
            final int bucketMillis = Math.round(bucket * 1000);
            // 引擎按 fontSize/nominal × EffectiveScreenScale 驱动 bucket（L69 forScale），
            // 模拟 RT 的 150% UI 须改屏幕缩放覆盖属性而非直接 forScale
            System.setProperty(EffectiveScreenScale.OVERRIDE_PROPERTY, Float.toString(bucket));
            try {
                final TextRenderState state = TextRenderState.builder("远行星号 Starsector 选项 MOD")
                        .draw(DRAW_X, DRAW_Y)
                        .fontSize(15f)
                        .textColor(0xFFFFFF, 1f)
                        .outlineColor(0x000000)
                        .shadowEnabled(true)
                        .shadowAlpha(1f)
                        .build();
                final List<TextPass> passes = TextLayoutEngine.layout(state, provider);
                assertFalse(passes.isEmpty(), "阴影启用时至少 2 个 pass");

                final Map<Integer, byte[]> textures = new HashMap<>();
                for (final GlyphAtlasPage page : AtlasTestHooks.pagesOfGroup(atlas, faceKey, bucketMillis, 0)) {
                    textures.put(page.textureId(), AtlasTestHooks.stagingSnapshot(page));
                }

                final int[] canvas = new int[CANVAS_W * CANVAS_H];
                java.util.Arrays.fill(canvas, CANVAS_BG);
                int quadCount = 0;
                for (final TextPass pass : passes) {
                    for (final GlyphQuad quad : pass.quads()) {
                        final byte[] staging = textures.get(quad.textureId());
                        assertNotNull(staging, "阴影复现 quad 的 textureId 必须能解析到图集页");
                        drawQuad(canvas, quad, staging);
                        quadCount++;
                    }
                }
                System.out.println("[softrender-shadow] bucket=" + bucket + " passes=" + passes.size()
                        + " quads=" + quadCount);
                writePng("softrender_shadow_bucket" + bucket + ".png", canvas, CANVAS_W, CANVAS_H, 2);
            } finally {
                System.setProperty(EffectiveScreenScale.OVERRIDE_PROPERTY, "1.0");
            }
        }
    }

    // ------------------------------------------------------------------
    // 任务 3d：阴影设备像素双线性复现（定位「逐字阴影不一致/毛边」）
    // ------------------------------------------------------------------

    /**
     * 以设备分辨率 + 双线性采样渲染阴影文本——与游戏内 GL 管线的采样方式一致
     * （任务 3c 的 1:1 最近邻复现不出亚像素相位问题）。PNG 即设备像素 1:1。
     */
    @Test
    void shadowPassDevicePixelBilinear() throws IOException {
        final int logicalW = 520;
        final int logicalH = 70;
        for (final float bucket : new float[]{1.0f, 1.5f}) {
            final int bucketMillis = Math.round(bucket * 1000);
            System.setProperty(EffectiveScreenScale.OVERRIDE_PROPERTY, Float.toString(bucket));
            try {
                final TextRenderState state = TextRenderState.builder("远行星号 Starsector 选项 MOD")
                        .draw(DRAW_X, DRAW_Y)
                        .fontSize(15f)
                        .textColor(0xFFFFFF, 1f)
                        .outlineColor(0x000000)
                        .shadowEnabled(true)
                        .shadowAlpha(1f)
                        .build();
                final List<TextPass> passes = TextLayoutEngine.layout(state, provider);
                assertFalse(passes.isEmpty());

                final Map<Integer, byte[]> textures = new HashMap<>();
                for (final GlyphAtlasPage page : AtlasTestHooks.pagesOfGroup(atlas, faceKey, bucketMillis, 0)) {
                    textures.put(page.textureId(), AtlasTestHooks.stagingSnapshot(page));
                }

                final int devW = Math.round(logicalW * bucket);
                final int devH = Math.round(logicalH * bucket);
                final int[] canvas = new int[devW * devH];
                java.util.Arrays.fill(canvas, CANVAS_BG);
                for (final TextPass pass : passes) {
                    for (final GlyphQuad quad : pass.quads()) {
                        final byte[] staging = textures.get(quad.textureId());
                        assertNotNull(staging, "设备像素复现 quad 的 textureId 必须能解析到图集页");
                        drawQuadDevice(canvas, devW, devH, quad, staging, bucket);
                    }
                }
                writePng("softrender_shadow_device_bucket" + bucket + ".png", canvas, devW, devH, 1);
            } finally {
                System.setProperty(EffectiveScreenScale.OVERRIDE_PROPERTY, "1.0");
            }
        }
    }

    /**
     * 设备分辨率软渲染单个 quad：逻辑坐标 × deviceScale 得设备像素矩形，
     * 双线性采样 staging alpha（texel 中心约定：c = v×page − 0.5），SRC_ALPHA 混合。
     */
    private static void drawQuadDevice(final int[] canvas, final int devW, final int devH,
                                       final GlyphQuad quad, final byte[] staging,
                                       final float deviceScale) {
        final float left = quad.x1() * deviceScale;
        final float right = quad.x3() * deviceScale;
        final float top = quad.y2() * deviceScale;
        final float bottom = quad.y1() * deviceScale;
        final float quadW = right - left;
        final float quadH = bottom - top;
        if (quadW <= 0f || quadH <= 0f) {
            return;
        }
        final float quadAlpha = ((quad.color() >>> 24) & 0xFF) / 255f;
        final float red = ((quad.color() >>> 16) & 0xFF) / 255f;
        final float green = ((quad.color() >>> 8) & 0xFF) / 255f;
        final float blue = (quad.color() & 0xFF) / 255f;

        final int x0 = Math.max(0, (int) Math.floor(left));
        final int x1 = Math.min(devW, (int) Math.ceil(right));
        final int y0 = Math.max(0, (int) Math.floor(top));
        final int y1 = Math.min(devH, (int) Math.ceil(bottom));
        for (int y = y0; y < y1; y++) {
            // 与 drawQuad 相同的采样方向：canvas 顶行（y2 侧）采 v1
            final float v = quad.v1() + ((y + 0.5f) - top) / quadH * (quad.v2() - quad.v1());
            final float texYf = v * ATLAS_PAGE_SIZE - 0.5f;
            final int ty0 = Math.min(ATLAS_PAGE_SIZE - 1, Math.max(0, (int) Math.floor(texYf)));
            final int ty1 = Math.min(ATLAS_PAGE_SIZE - 1, ty0 + 1);
            final float tyFrac = Math.min(1f, Math.max(0f, texYf - ty0));
            for (int x = x0; x < x1; x++) {
                final float u = quad.u2() + ((x + 0.5f) - left) / quadW * (quad.u3() - quad.u2());
                final float texXf = u * ATLAS_PAGE_SIZE - 0.5f;
                final int tx0 = Math.min(ATLAS_PAGE_SIZE - 1, Math.max(0, (int) Math.floor(texXf)));
                final int tx1 = Math.min(ATLAS_PAGE_SIZE - 1, tx0 + 1);
                final float txFrac = Math.min(1f, Math.max(0f, texXf - tx0));
                final float a00 = (staging[ty0 * ATLAS_PAGE_SIZE + tx0] & 0xFF) / 255f;
                final float a10 = (staging[ty0 * ATLAS_PAGE_SIZE + tx1] & 0xFF) / 255f;
                final float a01 = (staging[ty1 * ATLAS_PAGE_SIZE + tx0] & 0xFF) / 255f;
                final float a11 = (staging[ty1 * ATLAS_PAGE_SIZE + tx1] & 0xFF) / 255f;
                final float sampleAlpha = (a00 * (1 - txFrac) + a10 * txFrac) * (1 - tyFrac)
                        + (a01 * (1 - txFrac) + a11 * txFrac) * tyFrac;
                final float srcAlpha = sampleAlpha * quadAlpha;
                if (srcAlpha <= 0f) {
                    continue;
                }
                final int index = y * devW + x;
                canvas[index] = blendSrcAlpha(canvas[index], red, green, blue, srcAlpha);
            }
        }
    }

    /** 软件执行单个 quad：轴对齐矩形内按 UV 最近邻采样 staging，SRC_ALPHA 混合。 */
    private static void drawQuad(final int[] canvas, final GlyphQuad quad, final byte[] staging) {
        final float left = quad.x1();
        final float right = quad.x3();
        final float top = quad.y2();
        final float bottom = quad.y1();
        final float quadW = right - left;
        final float quadH = bottom - top;
        if (quadW <= 0f || quadH <= 0f) {
            return;
        }
        final float quadAlpha = ((quad.color() >>> 24) & 0xFF) / 255f;
        final float red = ((quad.color() >>> 16) & 0xFF) / 255f;
        final float green = ((quad.color() >>> 8) & 0xFF) / 255f;
        final float blue = (quad.color() & 0xFF) / 255f;

        final int x0 = Math.max(0, (int) Math.floor(left));
        final int x1 = Math.min(CANVAS_W, (int) Math.ceil(right));
        final int y0 = Math.max(0, (int) Math.floor(top));
        final int y1 = Math.min(CANVAS_H, (int) Math.ceil(bottom));
        for (int y = y0; y < y1; y++) {
            // 真实管线为 y-up（glOrtho 原点左下）：视觉顶点是 y 值大的 y1 侧，采样 v1（glyph 顶）。
            // 本画布直接把屏幕 y 当 PNG 行（全局垂直镜像真实屏幕），为让字形在 PNG 中端正可读，
            // 采样方向在 quad 内同步镜像：canvas 顶行（y2 侧）采 v1，底行（y1 侧）采 v2。
            final float v = quad.v1() + ((y + 0.5f) - top) / quadH * (quad.v2() - quad.v1());
            final int texY = Math.min(ATLAS_PAGE_SIZE - 1, Math.max(0, (int) (v * ATLAS_PAGE_SIZE)));
            for (int x = x0; x < x1; x++) {
                final float u = quad.u2() + ((x + 0.5f) - left) / quadW * (quad.u3() - quad.u2());
                final int texX = Math.min(ATLAS_PAGE_SIZE - 1, Math.max(0, (int) (u * ATLAS_PAGE_SIZE)));
                final float sampleAlpha = (staging[texY * ATLAS_PAGE_SIZE + texX] & 0xFF) / 255f;
                final float srcAlpha = sampleAlpha * quadAlpha;
                if (srcAlpha <= 0f) {
                    continue;
                }
                final int index = y * CANVAS_W + x;
                canvas[index] = blendSrcAlpha(canvas[index], red, green, blue, srcAlpha);
            }
        }
    }

    /** quad UV 对应的槽位区域内是否有任何非零 alpha（判断槽位是否为空白）。 */
    private static boolean slotRegionHasInk(final byte[] staging, final GlyphQuad quad) {
        final int tx0 = Math.max(0, (int) Math.floor(Math.min(quad.u1(), quad.u3()) * ATLAS_PAGE_SIZE));
        final int tx1 = Math.min(ATLAS_PAGE_SIZE, (int) Math.ceil(Math.max(quad.u1(), quad.u3()) * ATLAS_PAGE_SIZE));
        final int ty0 = Math.max(0, (int) Math.floor(Math.min(quad.v1(), quad.v2()) * ATLAS_PAGE_SIZE));
        final int ty1 = Math.min(ATLAS_PAGE_SIZE, (int) Math.ceil(Math.max(quad.v1(), quad.v2()) * ATLAS_PAGE_SIZE));
        for (int ty = ty0; ty < ty1; ty++) {
            for (int tx = tx0; tx < tx1; tx++) {
                if (staging[ty * ATLAS_PAGE_SIZE + tx] != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** SRC_ALPHA/ONE_MINUS_SRC_ALPHA 混合（RGB 与 alpha 通道同式）。 */
    private static int blendSrcAlpha(final int dst,
                                     final float srcR, final float srcG, final float srcB,
                                     final float srcA) {
        final float dstA = ((dst >>> 24) & 0xFF) / 255f;
        final float inv = 1f - srcA;
        final int a = Math.round((srcA + dstA * inv) * 255f);
        final int r = Math.round((srcR * srcA + (((dst >>> 16) & 0xFF) / 255f) * dstA * inv) * 255f);
        final int g = Math.round((srcG * srcA + (((dst >>> 8) & 0xFF) / 255f) * dstA * inv) * 255f);
        final int b = Math.round((srcB * srcA + ((dst & 0xFF) / 255f) * dstA * inv) * 255f);
        return (Math.min(255, a) << 24) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    /** 整画布内有墨（alpha 高于背景）的行区间：{首行, 末行}；无墨返回 {0,-1}。 */
    private static int[] inkedRowSpan(final int[] canvas) {
        int first = -1;
        int last = -1;
        for (int y = 0; y < CANVAS_H; y++) {
            boolean inked = false;
            for (int x = 0; x < CANVAS_W; x++) {
                if (canvas[y * CANVAS_W + x] != CANVAS_BG) {
                    inked = true;
                    break;
                }
            }
            if (inked) {
                if (first < 0) {
                    first = y;
                }
                last = y;
            }
        }
        return new int[]{Math.max(0, first), last};
    }

    /** 单个 quad 屏幕区域内着墨（与背景不同）的行数。 */
    private static int quadInkedRows(final int[] canvas, final GlyphQuad quad) {        final int x0 = Math.max(0, (int) Math.floor(quad.x1()));
        final int x1 = Math.min(CANVAS_W, (int) Math.ceil(quad.x3()));
        final int y0 = Math.max(0, (int) Math.floor(quad.y2()));
        final int y1 = Math.min(CANVAS_H, (int) Math.ceil(quad.y1()));
        int rows = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (canvas[y * CANVAS_W + x] != CANVAS_BG) {
                    rows++;
                    break;
                }
            }
        }
        return rows;
    }

    /** 位图源对照软渲染（仅 fixture png 存在时执行）：alpha 通道采样自 fnt 页 png。 */
    private static void renderBitmapControl(final Path pagePng) throws IOException {
        final BufferedImage page = ImageIO.read(pagePng.toFile());
        final int pageW = page.getWidth();
        final int pageH = page.getHeight();
        final TextRenderState state = TextRenderState.builder(RENDER_TEXT)
                .draw(DRAW_X, DRAW_Y)
                .fontSize(15f)
                .textColor(0xFFFFFF, 1f)
                .outlineColor(0x000000)
                .borderAlpha(1f)
                .borderEnabled(true)
                .build();
        final List<TextPass> passes = TextLayoutEngine.layout(state, fixtureMetrics);
        final int[] canvas = new int[CANVAS_W * CANVAS_H];
        java.util.Arrays.fill(canvas, CANVAS_BG);
        for (final TextPass pass : passes) {
            for (final GlyphQuad quad : pass.quads()) {
                drawQuadFromArgbPage(canvas, quad, page, pageW, pageH);
            }
        }
        writePng("softrender_bitmap.png", canvas, CANVAS_W, CANVAS_H, 1);
        System.out.println("[softrender] 位图源对照已写出（页 " + pageW + "x" + pageH + "）");
    }

    /** 位图路径 quad 采样：从 ARGB 页图取 alpha（fnt 页 chnl=15，字形在 alpha 通道）。 */
    private static void drawQuadFromArgbPage(final int[] canvas, final GlyphQuad quad,
                                             final BufferedImage page, final int pageW, final int pageH) {
        final float left = quad.x1();
        final float right = quad.x3();
        final float top = quad.y2();
        final float bottom = quad.y1();
        final float quadW = right - left;
        final float quadH = bottom - top;
        if (quadW <= 0f || quadH <= 0f) {
            return;
        }
        final float quadAlpha = ((quad.color() >>> 24) & 0xFF) / 255f;
        final float red = ((quad.color() >>> 16) & 0xFF) / 255f;
        final float green = ((quad.color() >>> 8) & 0xFF) / 255f;
        final float blue = (quad.color() & 0xFF) / 255f;
        final int x0 = Math.max(0, (int) Math.floor(left));
        final int x1 = Math.min(CANVAS_W, (int) Math.ceil(right));
        final int y0 = Math.max(0, (int) Math.floor(top));
        final int y1 = Math.min(CANVAS_H, (int) Math.ceil(bottom));
        for (int y = y0; y < y1; y++) {
            // 采样方向同 drawQuad（y-up 真实管线 → canvas 顶行采 v1）；
            // fnt 页 png 按非翻转上传约定直接以 v*pageH 索引图像行
            final float v = quad.v1() + ((y + 0.5f) - top) / quadH * (quad.v2() - quad.v1());
            final int texY = Math.min(pageH - 1, Math.max(0, (int) (v * pageH)));
            for (int x = x0; x < x1; x++) {
                final float u = quad.u2() + ((x + 0.5f) - left) / quadW * (quad.u3() - quad.u2());
                final int texX = Math.min(pageW - 1, Math.max(0, (int) (u * pageW)));
                final float sampleAlpha = ((page.getRGB(texX, texY) >>> 24) & 0xFF) / 255f;
                final float srcAlpha = sampleAlpha * quadAlpha;
                if (srcAlpha <= 0f) {
                    continue;
                }
                final int index = y * CANVAS_W + x;
                canvas[index] = blendSrcAlpha(canvas[index], red, green, blue, srcAlpha);
            }
        }
    }

    // ------------------------------------------------------------------
    // 任务 3b：CJK face 链回退（lte50549 只覆盖 Latin → MiSans fallback face）
    // ------------------------------------------------------------------

    @Test
    void nativeHasGlyphDistinguishesFontCoverage() {
        final long latinFace = NativeFontRasterizer.createFace(ttfPath, 15f, true);
        assertTrue(latinFace != 0L, "createFace(lte50549) 失败");
        final long cjkFace = NativeFontRasterizer.createFace(misansPath, 15f, true);
        assertTrue(cjkFace != 0L, "createFace(MiSans) 失败");
        try {
            assertTrue(NativeFontRasterizer.hasGlyph(latinFace, 'A'), "lte50549 含 Latin 字形");
            assertFalse(NativeFontRasterizer.hasGlyph(latinFace, 0x8FDC), "lte50549 不含 CJK（远）");
            assertTrue(NativeFontRasterizer.hasGlyph(cjkFace, 0x8FDC), "MiSans 含 CJK（远）");
            assertFalse(NativeFontRasterizer.hasGlyph(latinFace, 0), "码点 0 恒 false");
            assertFalse(NativeFontRasterizer.hasGlyph(0L, 'A'), "空句柄恒 false");
        } finally {
            NativeFontRasterizer.destroyFace(latinFace);
            NativeFontRasterizer.destroyFace(cjkFace);
        }
    }

    /** 端到端：「远行星号」四字经 face 链回退到 MiSans 栅格化，软渲染必须有墨。 */
    @Test
    void cjkTextRendersInkViaFallbackFaceChain() throws IOException {
        provider.forScale(1.0f);
        final TextRenderState state = TextRenderState.builder("远行星号")
                .draw(DRAW_X, DRAW_Y)
                .fontSize(15f)
                .textColor(0xFFFFFF, 1f)
                .build();
        final List<TextPass> passes = TextLayoutEngine.layout(state, provider);
        assertFalse(passes.isEmpty());

        final Map<Integer, byte[]> textures = new HashMap<>();
        for (final GlyphAtlasPage page : AtlasTestHooks.pagesOfGroup(atlas, faceKey, 1000, 0)) {
            textures.put(page.textureId(), AtlasTestHooks.stagingSnapshot(page));
        }

        final int[] canvas = new int[CANVAS_W * CANVAS_H];
        java.util.Arrays.fill(canvas, CANVAS_BG);
        int quads = 0;
        for (final TextPass pass : passes) {
            for (final GlyphQuad quad : pass.quads()) {
                final byte[] staging = textures.get(quad.textureId());
                assertNotNull(staging, "CJK quad 的 textureId 必须能解析到图集页");
                drawQuad(canvas, quad, staging);
                quads++;
            }
        }
        assertEquals(4, quads, "远行星号四字各产一个填充 quad");
        writePng("softrender_cjk_fallback.png", canvas, CANVAS_W, CANVAS_H, 1);

        int inked = 0;
        for (final int pixel : canvas) {
            if (pixel != CANVAS_BG) {
                inked++;
            }
        }
        System.out.println("[softrender-cjk] 远行星号有墨像素=" + inked);
        assertTrue(inked >= 200,
                "远行星号四字经 fallback face 栅格化后必须有墨，实际有墨像素=" + inked);
    }

    // ------------------------------------------------------------------
    // 任务 4：UV 提取一致性（槽位 UV 提取块 ≡ composeToFontBox 画布，逐字节）
    // ------------------------------------------------------------------

    @Test
    void slotUvExtractionMatchesComposeCanvas() {
        provider.forScale(1.0f);
        final List<Integer> slotted = new ArrayList<>();
        final Map<Integer, GlyphMetrics> metricsByCp = new HashMap<>();
        for (int cp = 32; cp <= 126; cp++) {
            final GlyphMetrics gm = provider.glyph(cp);
            if (gm != null && gm.width() > 0 && gm.height() > 0) {
                slotted.add(cp);
                metricsByCp.put(cp, gm);
            }
        }
        for (final int cp : commonCjkCodePoints(200)) {
            final GlyphMetrics gm = provider.glyph(cp);
            if (gm != null && gm.width() > 0 && gm.height() > 0) {
                slotted.add(cp);
                metricsByCp.put(cp, gm);
            }
        }
        assertTrue(slotted.size() >= 20, "入槽字形不足 20 个");

        // 纹理 id → staging 快照
        final Map<Integer, byte[]> textures = new HashMap<>();
        for (final GlyphAtlasPage page : AtlasTestHooks.pagesOfGroup(atlas, faceKey, 1000, 0)) {
            textures.put(page.textureId(), AtlasTestHooks.stagingSnapshot(page));
        }

        final float pixelSize = provider.calibratedPixelSize(ttfPath, 1000); // bucket 1.0
        final float cjkPixelSize = provider.calibratedPixelSize(misansPath, 1000);
        final int baseline = Math.round(fixtureFont.getBase());
        // 与 provider 的 face 链同序（lte50549 → MiSans）：CJK 槽位由 fallback face
        // 栅格化，期望像素块的重栅格化必须按同一链选择 face，否则必然不一致；
        // 像素字号必须与 provider 实际建 face 所用一致（含运行期校准因子）
        final long face = NativeFontRasterizer.createFace(ttfPath, pixelSize, true);
        assertTrue(face != 0L);
        final long cjkFace = NativeFontRasterizer.createFace(misansPath, cjkPixelSize, true);
        assertTrue(cjkFace != 0L);
        try {
            final Random random = new Random(42);
            final List<Integer> sampled = new ArrayList<>(slotted);
            for (int i = 0; i < 20; i++) {
                final int cp = sampled.remove(random.nextInt(sampled.size()));
                final GlyphMetrics gm = metricsByCp.get(cp);
                final byte[] staging = textures.get(gm.textureId());
                assertNotNull(staging, cpLabel(cp) + " 的 textureId 无对应页");

                // 槽位 UV → staging 像素块
                final int slotX = Math.round(gm.texX() * ATLAS_PAGE_SIZE);
                final int slotY = Math.round(gm.texY() * ATLAS_PAGE_SIZE);
                final int slotW = Math.round(gm.texWidth() * ATLAS_PAGE_SIZE);
                final int slotH = Math.round(gm.texHeight() * ATLAS_PAGE_SIZE);

                // 同参数重栅格化（face 链选择同 provider）+ 重合成 = 期望像素块（alpha 通道）
                final long inkFace = NativeFontRasterizer.hasGlyph(face, cp) ? face : cjkFace;
                final NativeGlyphBitmap ink = NativeFontRasterizer.rasterizeGlyph(inkFace, cp, baseline);
                assertNotNull(ink, cpLabel(cp) + " 重栅格化失败");
                final NativeGlyphBitmap expected =
                        provider.composeToFontBox(ink, fixtureMetrics.glyph(cp), 0, 1.0f);

                assertEquals(expected.width(), slotW, cpLabel(cp) + " 槽位宽 ≠ compose 画布宽");
                assertEquals(expected.height(), slotH, cpLabel(cp) + " 槽位高 ≠ compose 画布高");

                final int[] argb = expected.argbPixels();
                int mismatch = -1;
                for (int row = 0; row < slotH && mismatch < 0; row++) {
                    for (int col = 0; col < slotW; col++) {
                        // staging 行序 = GL 纹理行序（行 0 = v 0 = 字形底）：
                        // compose 画布第 row 行（行 0 = 字形顶）对应槽位内第 slotH-1-row 行
                        final int staged = staging[(slotY + slotH - 1 - row) * ATLAS_PAGE_SIZE + slotX + col] & 0xFF;
                        final int composed = (argb[row * slotW + col] >>> 24) & 0xFF;
                        if (staged != composed) {
                            mismatch = row * slotW + col;
                            break;
                        }
                    }
                }
                assertTrue(mismatch < 0,
                        cpLabel(cp) + " 槽位 UV 提取块与 compose 画布逐字节不一致，首个差异像素=" + mismatch);
            }
        } finally {
            NativeFontRasterizer.destroyFace(face);
            NativeFontRasterizer.destroyFace(cjkFace);
        }
        System.out.println("[uvcheck] 20 个抽样字形槽位 UV 提取块与 compose 画布逐字节一致");
    }

    // ------------------------------------------------------------------
    // PNG 输出与像素工具
    // ------------------------------------------------------------------

    private static String cpLabel(final int cp) {
        return cp >= 0x80
                ? String.format("U+%04X", cp)
                : "U+" + String.format("%04X", cp) + "('" + (char) cp + "')";
    }

    /** ARGB 像素中 alpha > 16 的行数。 */
    private static int inkedRows(final int[] argb, final int width, final int height) {
        int rows = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (((argb[y * width + x] >>> 24) & 0xFF) > 16) {
                    rows++;
                    break;
                }
            }
        }
        return rows;
    }

    private static int countNonZero(final byte[] data) {
        int count = 0;
        for (final byte b : data) {
            if (b != 0) {
                count++;
            }
        }
        return count;
    }

    /** ARGB → 灰度（取 alpha 通道，白字黑底）。 */
    private static int[] alphaToGray(final int[] argb) {
        final int[] gray = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            final int a = (argb[i] >>> 24) & 0xFF;
            gray[i] = 0xFF000000 | (a << 16) | (a << 8) | a;
        }
        return gray;
    }

    /** ALPHA8 字节 → 灰度。 */
    private static int[] alphaBytesToGray(final byte[] alpha) {
        final int[] gray = new int[alpha.length];
        for (int i = 0; i < alpha.length; i++) {
            final int a = alpha[i] & 0xFF;
            gray[i] = 0xFF000000 | (a << 16) | (a << 8) | a;
        }
        return gray;
    }

    /** staging 页（GL 行序：行 0 = v 0 = 字形底）垂直翻转为 PNG 阅读行序（行 0 = 字形顶）。 */
    private static byte[] flipStagingVertically(final byte[] staging) {
        final byte[] flipped = new byte[staging.length];
        for (int y = 0; y < ATLAS_PAGE_SIZE; y++) {
            System.arraycopy(staging, y * ATLAS_PAGE_SIZE,
                    flipped, (ATLAS_PAGE_SIZE - 1 - y) * ATLAS_PAGE_SIZE, ATLAS_PAGE_SIZE);
        }
        return flipped;
    }

    /** 写 PNG（scale 倍最近邻放大，便于人工检查）。 */
    private static void writePng(final String name,
                                 final int[] argb,
                                 final int width,
                                 final int height,
                                 final int scale) throws IOException {
        final int outW = width * scale;
        final int outH = height * scale;
        final BufferedImage image = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                image.setRGB(x, y, argb[(y / scale) * width + (x / scale)]);
            }
        }
        final Path out = dumpDir.resolve(name);
        ImageIO.write(image, "png", out.toFile());
        System.out.println("[png] " + out.toAbsolutePath());
    }
}
