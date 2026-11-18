package github.kasuminova.ssoptimizer.common.font;

import com.fs.graphics.font.BitmapFont;
import com.fs.graphics.font.BitmapGlyph;
import github.kasuminova.ssoptimizer.common.font.atlas.DynamicGlyphAtlas;
import github.kasuminova.ssoptimizer.common.font.atlas.GlyphSlot;
import github.kasuminova.ssoptimizer.common.render.engine.TextScaleBuckets;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphMetrics;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphProvider;
import github.kasuminova.ssoptimizer.common.font.layout.OutlineGlyphProvider;
import org.apache.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class TtfGlyphProvider implements OutlineGlyphProvider {
    private static final Logger LOGGER = Logger.getLogger(TtfGlyphProvider.class);

    private static final int WARMUP_BATCH_SIZE = 256;
    private static final int SPACE_CODE_POINT = 32;

    private final BitmapFont                                  baseFont;
    private final BitmapFontGlyphProvider                     metrics;
    private final OriginalGameFontOverrides.FontOverrideSpec  spec;
    private final List<Path>                                  fontFiles;
    private final Supplier<DynamicGlyphAtlas>                 atlas;
    private final TtfRasterBackend                            backend;
    private final boolean                                     victorFamily;
    private final String                                      faceKey;
    private final FontLayoutTable                             layoutTable;

    private final Map<Integer, long[]> faces = new HashMap<>();
    private final Map<Integer, BitmapGlyph> missingMetrics = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean warmupStarted = new java.util.concurrent.atomic.AtomicBoolean();

    private final float[] calibrationFactors;

    /** 仅逻辑线程可写，预热线程启动时读取快照。 */
    private int currentBucketMillis = TextScaleBuckets.bucketScaleMillis(1.0f);

    /** 图集代际变化时整体失效，textureId==0 的槽位不缓存，避免保留失效纹理或将无效 id 烘焙到后续帧。 */
    private final Map<Long, GlyphMetrics> slotCache = new HashMap<>();
    private int slotCacheGeneration = -1;
    private static final int SLOT_CACHE_MAX = 4096;

    public TtfGlyphProvider(final BitmapFont baseFont,
                            final OriginalGameFontOverrides.FontOverrideSpec spec,
                            final Path fontDir,
                            final DynamicGlyphAtlas atlas) {
        this(baseFont, spec, fontDir, atlas, new NativeTtfRasterBackend());
    }

    TtfGlyphProvider(final BitmapFont baseFont,
                     final OriginalGameFontOverrides.FontOverrideSpec spec,
                     final Path fontDir,
                     final DynamicGlyphAtlas atlas,
                     final TtfRasterBackend backend) {
        this(baseFont, spec, fontDir, () -> atlas, backend);
    }

    TtfGlyphProvider(final BitmapFont baseFont, final OriginalGameFontOverrides.FontOverrideSpec spec,
                     final Path fontDir, final Supplier<DynamicGlyphAtlas> atlas) {
        this(baseFont, spec, fontDir, atlas, new NativeTtfRasterBackend());
    }

    private TtfGlyphProvider(final BitmapFont baseFont, final OriginalGameFontOverrides.FontOverrideSpec spec,
                             final Path fontDir, final Supplier<DynamicGlyphAtlas> atlas, final TtfRasterBackend backend) {
        this.baseFont = baseFont;
        this.metrics = new BitmapFontGlyphProvider(baseFont);
        this.spec = spec;
        this.atlas = atlas;
        this.backend = backend;
        this.faceKey = spec.normalizedOriginalFontPath();
        final var table = FontLayoutTable.forPath(faceKey);
        this.layoutTable = table != null && Math.abs(table.size()) == baseFont.getNominalFontSize()
                && table.lineHeight() == baseFont.getLineHeight() && table.baseline() == baseFont.getBase() ? table : null;
        this.victorFamily = TtfBmFontGenerator.isVictorManagedFontPath(faceKey);
        this.fontFiles = resolveFontFiles(spec, fontDir);
        this.calibrationFactors = new float[fontFiles.size()];
        Arrays.fill(calibrationFactors, -1f);
    }

    private static List<Path> resolveFontFiles(final OriginalGameFontOverrides.FontOverrideSpec spec,
                                               final Path fontDir) {
        final List<Path> files = new ArrayList<>();
        for (final String candidate : spec.allFontCandidates()) {
            final Path path = spec.resolveCandidate(fontDir, candidate);
            if (Files.isRegularFile(path) && !files.contains(path)) {
                files.add(path);
            }
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("[SSOptimizer] TTF 字形源找不到字体文件: spec="
                    + spec.normalizedOriginalFontPath() + " fontDir=" + fontDir
                    + " candidates=" + spec.allFontCandidates());
        }
        return files;
    }

    @Override
    public GlyphMetrics glyph(final int codePoint) {
        final GlyphMetrics base = baseMetrics(codePoint);
        if (base == null) {
            return null;
        }
        if (TtfBmFontGenerator.shouldTreatAsSpaceGlyph(codePoint)) {
            if (layoutTable != null && layoutTable.cells().containsKey(codePoint))
                return new GlyphMetrics(base.xOffset(), base.xAdvance(), 0, 0, 0, 0, 0, 0, 0, 0);
            final GlyphMetrics space = metrics.glyph(SPACE_CODE_POINT);
            final float advance = space != null ? space.xAdvance() : base.xAdvance();
            return new GlyphMetrics(0, advance, 0, 0, 0, 0f, 0f, 0f, 0f, 0);
        }
        // 原版占位字形只保留步进，不生成图集槽位或可见 quad。
        if (base.width() <= 1 && base.height() <= 1) {
            return new GlyphMetrics(base.xOffset(), base.xAdvance(), base.bearingY(),
                    0, 0, 0f, 0f, 0f, 0f, 0);
        }

        return cachedSlotMetrics(codePoint, base, 0);
    }

    @Override
    public GlyphMetrics strokedGlyph(final int codePoint, final float strokeWidthLogicalPx) {
        final GlyphMetrics base = baseMetrics(codePoint);
        if (base == null || (base.width() <= 1 && base.height() <= 1)) {
            return null;
        }
        final float bucketScale = currentBucketScale();
        final int strokeBucketMillis = Math.round(
                TextScaleBuckets.quantizeStrokeDevicePx(strokeWidthLogicalPx, bucketScale) * 1000f);
        return cachedSlotMetrics(codePoint, base, strokeBucketMillis);
    }

    private GlyphMetrics baseMetrics(final int codePoint) {
        final GlyphMetrics existing = metrics.glyph(codePoint);
        if (existing != null && !(Character.isIdeographic(codePoint)
                && TtfBmFontGenerator.shouldPreserveSourceSpecialGlyph((int) existing.width(), (int) existing.height()))) return existing;
        final BitmapGlyph glyph = missingGlyphMetrics(codePoint);
        return glyph == null ? null : new GlyphMetrics(glyph.getXOffset(), glyph.getXAdvance(), glyph.getBearingY(),
                glyph.getWidth(), glyph.getHeight(), 0, 0, 0, 0, 0);
    }

    BitmapGlyph missingGlyphMetrics(final int codePoint) {
        if (!Character.isValidCodePoint(codePoint) || Character.isISOControl(codePoint)
                || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) return null;
        synchronized (faces) {
            if (missingMetrics.containsKey(codePoint)) return missingMetrics.get(codePoint);
            final int cp = victorFamily ? TtfBmFontGenerator.substituteVictorLowercaseCodePoint(codePoint) : codePoint;
            final long face = faceForGlyphLocked(faceChainForBucketLocked(1000), 1000, cp);
            BitmapGlyph glyph = null;
            if (face != 0L) {
                final NativeGlyphBitmap ink = backend.rasterize(face, cp, baseFont.getBase(), 0f);
                if (ink != null) {
                    glyph = new BitmapGlyph();
                    glyph.setGlyphId(codePoint);
                    glyph.setXOffset(ink.xOffset());
                    glyph.setXAdvance(TtfBmFontGenerator.encodedXAdvanceForRuntimeLayout(ink.xAdvance(), ink.xOffset()));
                    glyph.setBearingY(ink.yOffset());
                    glyph.setWidth(ink.width());
                    glyph.setHeight(ink.height());
                    final var cell = layoutTable == null ? null : layoutTable.cells().get(codePoint);
                    if (cell != null && !TtfBmFontGenerator.shouldPreserveSourceSpecialGlyph(cell.width(), cell.height())) {
                        glyph.setXOffset(cell.left());
                        glyph.setXAdvance(cell.encodedAdvance());
                        glyph.setBearingY(cell.top());
                        glyph.setWidth(cell.width());
                        glyph.setHeight(cell.height());
                    }
                }
            }
            if (missingMetrics.size() >= SLOT_CACHE_MAX) missingMetrics.clear();
            missingMetrics.put(codePoint, glyph);
            return glyph;
        }
    }

    @Override
    public Integer kerning(final int prevCodePoint, final int codePoint) {
        return metrics.kerning(prevCodePoint, codePoint);
    }

    @Override
    public int nominalFontSize() {
        return metrics.nominalFontSize();
    }

    @Override
    public int lineHeight() {
        return metrics.lineHeight();
    }

    @Override
    public GlyphProvider forScale(final float scale) {
        currentBucketMillis = TextScaleBuckets.bucketScaleMillis(scale * EffectiveScreenScale.current());
        return this;
    }

    @Override
    public void flushPendingUploads() {
        atlas.get().flushDirty();
    }

    @Override
    public boolean usesAtlasTexture() {
        return true;
    }

    @Override
    public boolean synthesizesOutline() {
        return true;
    }

    @Override
    public float currentBucketScale() {
        return currentBucketMillis / 1000.0f;
    }

    String fontFileName() {
        return fontFiles.get(0).getFileName().toString();
    }

    float calibratedPixelSize(final Path fontFile, final int bucketMillis) {
        synchronized (faces) {
            final int index = fontFiles.indexOf(fontFile);
            if (index < 0) {
                throw new IllegalArgumentException("fontFile 不在 provider 字体链中: " + fontFile);
            }
            return Math.max(1f, nominalFontSize()
                    * calibrationFactorLocked(index) * (bucketMillis / 1000.0f));
        }
    }

    public void startWarmup() {
        if (warmupStarted.get() || !warmupStarted.compareAndSet(false, true)) return;
        Thread.ofVirtual()
              .name("SSOptimizer-FontWarmup-" + faceKey.substring(faceKey.lastIndexOf('/') + 1))
              .start(this::warmupCommonGlyphs);
    }

    private void warmupCommonGlyphs() {
        final long startNanos = System.nanoTime();
        try {
            final int bucketMillis = currentBucketMillis;
            final List<Integer> codePoints = collectWarmupCodePoints();
            int rasterized = 0;
            for (int from = 0; from < codePoints.size(); from += WARMUP_BATCH_SIZE) {
                final int to = Math.min(codePoints.size(), from + WARMUP_BATCH_SIZE);
                rasterized += warmupBatch(codePoints.subList(from, to), bucketMillis);
            }
            LOGGER.info("[SSOptimizer] TTF 字形源预热完成: " + faceKey
                    + " glyphs=" + rasterized + "/" + codePoints.size()
                    + " bucket=" + TextScaleBuckets.bucketLabel(bucketMillis)
                    + " elapsed=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos) + "ms");
        } catch (RuntimeException e) {
            LOGGER.warn("[SSOptimizer] TTF 字形源预热失败（不影响运行，字形将按需栅格化）: " + faceKey, e);
        }
    }

    private List<Integer> collectWarmupCodePoints() {
        final List<Integer> codePoints = new ArrayList<>();
        final BitmapGlyph[] glyphs = baseFont.getGlyphs();
        for (int cp = 0; cp < glyphs.length; cp++) {
            final BitmapGlyph glyph = glyphs[cp];
            if (glyph == null || glyph.getWidth() == 0 || glyph.getHeight() == 0) {
                continue;
            }
            if (!isWarmupCodePoint(cp)) {
                continue;
            }
            codePoints.add(cp);
        }
        return codePoints;
    }

    private static boolean isWarmupCodePoint(final int codePoint) {
        return (codePoint >= 32 && codePoint <= 126)
                || (codePoint >= 0x2E80 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3000 && codePoint <= 0x303F)
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);
    }

    private int warmupBatch(final List<Integer> batch, final int bucketMillis) {
        final int[] rasterCodePoints = new int[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            final int cp = batch.get(i);
            rasterCodePoints[i] = victorFamily ? TtfBmFontGenerator.substituteVictorLowercaseCodePoint(cp) : cp;
        }
        final int baseline = baselineForBucket(bucketMillis);
        final NativeGlyphBitmap[] bitmaps = new NativeGlyphBitmap[batch.size()];
        synchronized (faces) {
            final long[] chain = faceChainForBucketLocked(bucketMillis);
            final Map<Long, List<Integer>> indicesByFace = new HashMap<>();
            for (int i = 0; i < rasterCodePoints.length; i++) {
                final long face = faceForGlyphLocked(chain, bucketMillis, rasterCodePoints[i]);
                if (face != 0L) {
                    indicesByFace.computeIfAbsent(face, key -> new ArrayList<>()).add(i);
                }
            }
            if (indicesByFace.isEmpty()) {
                LOGGER.warn("[SSOptimizer] TTF 预热中止：face 链创建失败或全无覆盖: "
                        + faceKey + " bucket=" + bucketMillis);
                return 0;
            }
            for (final Map.Entry<Long, List<Integer>> entry : indicesByFace.entrySet()) {
                final List<Integer> indices = entry.getValue();
                final int[] codePoints = new int[indices.size()];
                for (int i = 0; i < indices.size(); i++) {
                    codePoints[i] = rasterCodePoints[indices.get(i)];
                }
                final NativeGlyphBitmap[] partial =
                        backend.rasterizeBatch(entry.getKey(), codePoints, baseline, 0f);
                if (partial == null) {
                    LOGGER.warn("[SSOptimizer] TTF 预热批量栅格化失败: " + faceKey + " bucket=" + bucketMillis);
                    continue;
                }
                for (int i = 0; i < indices.size(); i++) {
                    bitmaps[indices.get(i)] = partial[i];
                }
            }
        }
        int stored = 0;
        for (int i = 0; i < batch.size(); i++) {
            final NativeGlyphBitmap bitmap = bitmaps[i];
            if (bitmap == null) {
                continue;
            }
            final GlyphMetrics base = metrics.glyph(batch.get(i));
            if (base == null || base.width() == 0 || base.height() == 0) {
                continue;
            }
            final NativeGlyphBitmap composed = composeGlyph(batch.get(i), bitmap, base, 0, bucketMillis / 1000.0f);
            final GlyphSlot slot = atlas.get().request(faceKey, bucketMillis, 0, batch.get(i), baseline,
                    (cp, bl, stroke) -> composed);
            if (slot != null) {
                stored++;
            }
        }
        return stored;
    }

    private GlyphMetrics cachedSlotMetrics(final int rasterCodePoint,
                                           final GlyphMetrics base,
                                           final int strokeBucketMillis) {
        final int generation = atlas.get().textureGeneration();
        if (generation != slotCacheGeneration) {
            slotCache.clear();
            slotCacheGeneration = generation;
        }
        final long key = ((long) currentBucketMillis << 41)
                | ((long) strokeBucketMillis << 21) | rasterCodePoint;
        final GlyphMetrics cached = slotCache.get(key);
        if (cached != null) {
            return cached;
        }
        final GlyphSlot slot = requestSlot(rasterCodePoint, base, strokeBucketMillis);
        if (slot == null) {
            return null;
        }
        final GlyphMetrics result = slotMetrics(base, slot, currentBucketScale());
        if (result.textureId() != 0) {
            if (slotCache.size() >= SLOT_CACHE_MAX) {
                slotCache.clear();
            }
            slotCache.put(key, result);
        }
        return result;
    }

    private GlyphSlot requestSlot(final int rasterCodePoint,
                                  final GlyphMetrics base,
                                  final int strokeBucketMillis) {
        final int baseline = baselineForBucket(currentBucketMillis);
        final float bucketScale = currentBucketScale();
        synchronized (faces) {
            final long[] chain = faceChainForBucketLocked(currentBucketMillis);
            return atlas.get().request(faceKey, currentBucketMillis, strokeBucketMillis, rasterCodePoint, baseline,
                    (cp, bl, stroke) -> {
                        final int rasterCp = victorFamily ? TtfBmFontGenerator.substituteVictorLowercaseCodePoint(cp) : cp;
                         final long face = faceForGlyphLocked(chain, currentBucketMillis, rasterCp);
                        if (face == 0L) {
                            return null;
                        }
                        final NativeGlyphBitmap bitmap = backend.rasterize(face, rasterCp, bl, stroke);
                        return bitmap == null ? null : composeGlyph(cp, bitmap, base, strokeBucketMillis, bucketScale);
                    });
        }
    }

    private long[] faceChainForBucketLocked(final int bucketMillis) {
        long[] chain = faces.get(bucketMillis);
        if (chain == null) {
            chain = new long[fontFiles.size()];
            Arrays.fill(chain, -1L);
            faces.put(bucketMillis, chain);
        }
        return chain;
    }

    private long faceAtLocked(final long[] chain, final int index, final int bucketMillis) {
        long face = chain[index];
        if (face == -1L) {
            final float pixelSize = Math.max(1f, nominalFontSize()
                    * calibrationFactorLocked(index) * (bucketMillis / 1000.0f));
            face = backend.createFace(fontFiles.get(index), pixelSize);
            if (face != 0L) {
                chain[index] = face;
            }
        }
        return face;
    }

    private float calibrationFactorLocked(final int index) {
        final float cached = calibrationFactors[index];
        if (cached > 0f) {
            return cached;
        }
        float factor = 1f;
        final float nominal = nominalFontSize();
        final long probe = backend.createFace(fontFiles.get(index), nominal);
        if (probe == 0L) {
            LOGGER.warn("[SSOptimizer] TTF 校准探针 face 创建失败，按因子 1.0 处理: "
                    + fontFiles.get(index) + " face=" + faceKey);
        } else {
            try {
                factor = index == 0
                        ? primaryCalibrationFactorLocked(probe)
                        : fallbackCalibrationFactorLocked(probe);
                if (factor != 1f) {
                    LOGGER.info("[SSOptimizer] TTF 运行期校准: " + fontFiles.get(index).getFileName()
                            + " face=" + faceKey + " factor=" + factor);
                }
            } finally {
                backend.destroyFace(probe);
            }
        }
        calibrationFactors[index] = factor;
        return factor;
    }

    private float primaryCalibrationFactorLocked(final long probe) {
        final float fntAvg = averageFntAdvance(TtfBmFontGenerator.PRIMARY_ADVANCE_SAMPLE);
        final float nativeAvg = averageProbeAdvance(probe, TtfBmFontGenerator.PRIMARY_ADVANCE_SAMPLE);
        if (fntAvg <= 0f || nativeAvg <= 0f) {
            LOGGER.warn("[SSOptimizer] TTF 主字体校准探针无有效样本，按因子 1.0 处理: face=" + faceKey
                    + " fntAvg=" + fntAvg + " nativeAvg=" + nativeAvg);
            return 1f;
        }
        final float raw = fntAvg / nativeAvg;
        if (Math.abs(raw - 1.0f) < 0.02f) {
            return 1f;
        }
        return Math.max(TtfBmFontGenerator.MIN_PRIMARY_ADVANCE_SCALE,
                Math.min(TtfBmFontGenerator.MAX_PRIMARY_ADVANCE_SCALE, raw));
    }

    private float fallbackCalibrationFactorLocked(final long probe) {
        final float fntPrimaryAvg = averageFntAdvance(TtfBmFontGenerator.PRIMARY_ADVANCE_SAMPLE);
        final float fntFallbackAvg = averageFntAdvance(TtfBmFontGenerator.FALLBACK_VISUAL_SAMPLE);
        final float nativeFallbackAvg = averageProbeAdvance(probe, TtfBmFontGenerator.FALLBACK_VISUAL_SAMPLE);
        final float primaryFactor = calibrationFactorLocked(0);
        final long primaryProbe = backend.createFace(fontFiles.get(0),
                Math.max(1f, nominalFontSize() * primaryFactor));
        if (primaryProbe == 0L) {
            LOGGER.warn("[SSOptimizer] TTF fallback 校准的主字体探针创建失败，按因子 1.0 处理: face=" + faceKey);
            return 1f;
        }
        final float primaryRenderedAvg;
        try {
            primaryRenderedAvg = averageProbeAdvance(primaryProbe, TtfBmFontGenerator.PRIMARY_ADVANCE_SAMPLE);
        } finally {
            backend.destroyFace(primaryProbe);
        }
        if (fntPrimaryAvg <= 0f || fntFallbackAvg <= 0f
                || nativeFallbackAvg <= 0f || primaryRenderedAvg <= 0f) {
            LOGGER.warn("[SSOptimizer] TTF fallback 校准探针无有效样本，按因子 1.0 处理: face=" + faceKey
                    + " fntPrimaryAvg=" + fntPrimaryAvg + " fntFallbackAvg=" + fntFallbackAvg
                    + " nativeFallbackAvg=" + nativeFallbackAvg
                    + " primaryRenderedAvg=" + primaryRenderedAvg);
            return 1f;
        }
        final float target = primaryRenderedAvg * (fntFallbackAvg / fntPrimaryAvg);
        final float raw = target / nativeFallbackAvg;
        if (Math.abs(raw - 1.0f) < 0.02f) {
            return 1f;
        }
        return Math.max(TtfBmFontGenerator.MIN_FALLBACK_VISUAL_SCALE,
                Math.min(TtfBmFontGenerator.MAX_FALLBACK_VISUAL_SCALE, raw));
    }

    private float averageFntAdvance(final String sample) {
        float sum = 0f;
        int count = 0;
        for (final int cp : sample.codePoints().toArray()) {
            final GlyphMetrics gm = metrics.glyph(cp);
            if (gm == null || gm.xAdvance() <= 0f) {
                continue;
            }
            sum += gm.xOffset() + gm.xAdvance();
            count++;
        }
        return count == 0 ? 0f : sum / count;
    }

    private float averageProbeAdvance(final long face, final String sample) {
        float sum = 0f;
        int count = 0;
        for (final int cp : sample.codePoints().toArray()) {
            if (!backend.hasGlyph(face, cp)) {
                continue;
            }
            final float advance = backend.probeAdvance(face, cp);
            if (advance <= 0f) {
                continue;
            }
            sum += advance;
            count++;
        }
        return count == 0 ? 0f : sum / count;
    }

    private long faceForGlyphLocked(final long[] chain, final int bucketMillis, final int codePoint) {
        for (int i = 0; i < chain.length; i++) {
            final long face = faceAtLocked(chain, i, bucketMillis);
            if (face != 0L && backend.hasGlyph(face, codePoint)) {
                return face;
            }
        }
        return 0L;
    }

    private int baselineForBucket(final int bucketMillis) {
        return Math.round(baseFont.getBase() * (bucketMillis / 1000.0f));
    }

    private NativeGlyphBitmap composeGlyph(int cp, NativeGlyphBitmap ink, GlyphMetrics base, int stroke, float scale) {
        final var cell = layoutTable == null ? null : layoutTable.cells().get(cp);
        if (cell == null || TtfBmFontGenerator.shouldPreserveSourceSpecialGlyph(cell.width(), cell.height()) || !ink.hasImage())
            return composeToFontBox(ink, base, stroke, scale);
        final int border = Math.round(stroke / 1000f);
        final int width = Math.max(1, Math.round(cell.width() * scale) + 2 * border);
        final int height = Math.max(1, Math.round(cell.height() * scale) + 2 * border);
        final int left = Math.round(cell.left() * scale) - border;
        final int top = Math.round(cell.top() * scale) - border;
        final int[] pixels = ink.argbPixels();
        if (ink.width() == width && ink.height() == height)
            return new NativeGlyphBitmap(width, height, pixels, left, top, ink.xAdvance());
        final var image = new java.awt.image.BufferedImage(ink.width(), ink.height(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, ink.width(), ink.height(), pixels, 0, ink.width());
        final var fitted = FontLayoutTable.fitInk(image, width, height);
        return new NativeGlyphBitmap(width, height, fitted.getRGB(0, 0, width, height, null, 0, width), left, top, ink.xAdvance());
    }

    /** 画布必须取缩放 fnt 盒与实际墨迹的并集，避免 FreeType hinting 产生的盒外像素被裁剪。 */
    NativeGlyphBitmap composeToFontBox(final NativeGlyphBitmap ink,
                                       final GlyphMetrics base,
                                       final int strokeBucketMillis,
                                       final float bucketScale) {
        final int expand = Math.round(strokeBucketMillis / 1000f);
        final int fntLeft = Math.round(base.xOffset() * bucketScale) - expand;
        final int fntTop = Math.round(base.bearingY() * bucketScale) - expand;
        final int fntWidth = Math.max(1, Math.round(base.width() * bucketScale) + expand * 2);
        final int fntHeight = Math.max(1, Math.round(base.height() * bucketScale) + expand * 2);

        final int inkPosX;
        if (victorFamily && ink.hasImage()) {
            inkPosX = centeredInkLeftPx(
                    Math.round((base.xOffset() + base.xAdvance()) * bucketScale), ink.width());
        } else {
            inkPosX = ink.xOffset();
        }

        int left = fntLeft;
        int top = fntTop;
        int right = fntLeft + fntWidth;
        int bottom = fntTop + fntHeight;
        if (ink.hasImage()) {
            left = Math.min(left, inkPosX);
            top = Math.min(top, ink.yOffset());
            right = Math.max(right, inkPosX + ink.width());
            bottom = Math.max(bottom, ink.yOffset() + ink.height());
        }
        final int canvasWidth = right - left;
        final int canvasHeight = bottom - top;

        final int[] canvas = new int[canvasWidth * canvasHeight];
        if (ink.hasImage()) {
            final int destX = inkPosX - left;
            final int destY = ink.yOffset() - top;
            final int[] pixels = ink.argbPixels();
            for (int row = 0; row < ink.height(); row++) {
                System.arraycopy(pixels, row * ink.width(),
                        canvas, (destY + row) * canvasWidth + destX, ink.width());
            }
        }
        return new NativeGlyphBitmap(canvasWidth, canvasHeight, canvas, left, top, 0);
    }

    static int centeredInkLeftPx(final int cellWidthPx, final int inkWidthPx) {
        return Math.round((cellWidthPx - inkWidthPx) / 2.0f);
    }

    private static GlyphMetrics slotMetrics(final GlyphMetrics base, final GlyphSlot slot,
                                            final float bucketScale) {
        final float invBucket = 1.0f / bucketScale;
        final float xOffset = slot.originX() * invBucket;
        return new GlyphMetrics(
                xOffset,
                base.xAdvance() + (base.xOffset() - xOffset),
                slot.originY() * invBucket,
                slot.width() * invBucket,
                slot.height() * invBucket,
                slot.texX(), slot.texY(), slot.texWidth(), slot.texHeight(),
                slot.textureId());
    }

    /** 所有后端调用必须由调用方在 faces 锁内串行化。 */
    interface TtfRasterBackend {
        long createFace(Path fontFile, float pixelSize);

        boolean hasGlyph(long face, int codePoint);

        float probeAdvance(long face, int codePoint);

        NativeGlyphBitmap rasterize(long face, int codePoint, int baseline, float strokeWidthPx);

        NativeGlyphBitmap[] rasterizeBatch(long face, int[] codePoints, int baseline, float strokeWidthPx);

        void destroyFace(long face);
    }

    private static final class NativeTtfRasterBackend implements TtfRasterBackend {
        @Override
        public long createFace(final Path fontFile, final float pixelSize) {
            return NativeFontRasterizer.createFace(fontFile, pixelSize, true);
        }

        @Override
        public boolean hasGlyph(final long face, final int codePoint) {
            return NativeFontRasterizer.hasGlyph(face, codePoint);
        }

        @Override
        public float probeAdvance(final long face, final int codePoint) {
            final NativeGlyphBitmap probe = NativeFontRasterizer.rasterizeGlyph(face, codePoint, 0);
            return probe == null ? 0f : probe.xAdvance();
        }

        @Override
        public NativeGlyphBitmap rasterize(final long face, final int codePoint,
                                           final int baseline, final float strokeWidthPx) {
            return strokeWidthPx > 0f
                    ? NativeFontRasterizer.rasterizeGlyphStroked(face, codePoint, baseline, strokeWidthPx)
                    : NativeFontRasterizer.rasterizeGlyph(face, codePoint, baseline);
        }

        @Override
        public NativeGlyphBitmap[] rasterizeBatch(final long face, final int[] codePoints,
                                                  final int baseline, final float strokeWidthPx) {
            return NativeFontRasterizer.rasterizeGlyphs(face, codePoints, baseline, strokeWidthPx);
        }

        @Override
        public void destroyFace(final long face) {
            NativeFontRasterizer.destroyFace(face);
        }
    }
}
