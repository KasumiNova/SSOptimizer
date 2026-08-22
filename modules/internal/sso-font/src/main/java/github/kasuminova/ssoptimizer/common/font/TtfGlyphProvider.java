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

/**
 * TTF 动态图集字形源：原版覆盖表命中的字体（insignia/orbitron/victor 族）的
 * {@code GlyphProvider} 实现，字形像素来自 native FreeType 按尺寸档位精确栅格化进
 * 动态图集，排版度量仍取自基底 BitmapFont（生成的 fnt）。
 * <p>
 * 动机（设计文档 §4.3/§4.6）：位图放缩采样在 scale≠1 时模糊，动态图集按
 * 「名义字号 × 有效屏幕缩放」的 bucket 目标像素尺寸精确栅格化；
 * 度量继续用 fnt 值保证 drawTextWrapped 换行与布局坐标与原版逐像素一致，
 * 图集位图按 fnt 字形盒 × bucketScale 合成（GPU 降采样，超采样定案）。
 * <p>
 * 线程模型：渲染调用（glyph/kerning/strokedGlyph/flushPendingUploads）只在逻辑线程；
 * {@link #startWarmup()} 的 CJK 预热在 daemon 线程，face 句柄访问经内部锁与逻辑线程互斥；
 * currentBucket 为逻辑线程可变状态（见 {@link #forScale(float)}）。
 */
public final class TtfGlyphProvider implements OutlineGlyphProvider {
    private static final Logger LOGGER = Logger.getLogger(TtfGlyphProvider.class);

    /** CJK 预热批量栅格化的单批码点数（摊薄 JNI 边界，同时控制单次锁持有时长）。 */
    private static final int WARMUP_BATCH_SIZE = 256;
    /** 空格码点：'{'/'}' 空格化的 advance 来源。 */
    private static final int SPACE_CODE_POINT = 32;

    private final BitmapFont                                  baseFont;
    private final BitmapFontGlyphProvider                     metrics;
    private final OriginalGameFontOverrides.FontOverrideSpec  spec;
    /** face 链的字体文件（spec.allFontCandidates() 顺序去重、仅存在的文件；至少一个）。 */
    private final List<Path>                                  fontFiles;
    private final DynamicGlyphAtlas                           atlas;
    private final TtfRasterBackend                            backend;
    private final boolean                                     victorFamily;
    private final String                                      faceKey;

    /**
     * sizeBucketMillis → face 链（与 {@link #fontFiles} 等长；-1 = 尚未创建，
     * 0 不出现——创建失败不留缓存下次重试）；同时是 face 访问的互斥锁对象。
     */
    private final Map<Integer, long[]> faces = new HashMap<>();

    /** 每字体文件的运行期校准因子（-1 = 尚未计算；只在 faces 锁内访问）。 */
    private final float[] calibrationFactors;

    /** 当前尺寸档位（逻辑线程可变，forScale 设置；预热线程启动时快照一次）。 */
    private int currentBucketMillis = TextScaleBuckets.bucketScaleMillis(1.0f);

    /**
     * 槽位度量直通缓存（仅逻辑线程访问）：key = bucketMillis<<41 | strokeBucketMillis<<21
     * | 码点。绕过 atlas.request 的全图集锁与双 Map 查找——布局期同一 (bucket, stroke,
     * 码点) 会被各 pass 与逐帧 render 反复查询（profiling 热点 LinkedHashMap.get）。
     * 失效由图集纹理代际守卫（淘汰/上下文重建时 textureGeneration 递增 → 整体清空），
     * 代际不变则槽位与 textureId 恒有效。textureId==0（预热线程建槽未建纹理的窗口期）
     * 的结果不入缓存，避免把 0 号纹理烘进后续帧的 quad。
     */
    private final Map<Long, GlyphMetrics> slotCache = new HashMap<>();
    /** slotCache 对应的图集纹理代际（-1 = 未初始化）。 */
    private int slotCacheGeneration = -1;
    /** slotCache 容量上限：超出即清空重建（稳态工作集为可见文本字符集，数百项）。 */
    private static final int SLOT_CACHE_MAX = 4096;

    public TtfGlyphProvider(final BitmapFont baseFont,
                            final OriginalGameFontOverrides.FontOverrideSpec spec,
                            final Path fontDir,
                            final DynamicGlyphAtlas atlas) {
        this(baseFont, spec, fontDir, atlas, new NativeTtfRasterBackend());
    }

    /**
     * 测试通道：注入内存假栅格后端，不依赖 native 库。
     */
    TtfGlyphProvider(final BitmapFont baseFont,
                     final OriginalGameFontOverrides.FontOverrideSpec spec,
                     final Path fontDir,
                     final DynamicGlyphAtlas atlas,
                     final TtfRasterBackend backend) {
        this.baseFont = baseFont;
        this.metrics = new BitmapFontGlyphProvider(baseFont);
        this.spec = spec;
        this.atlas = atlas;
        this.backend = backend;
        this.faceKey = spec.normalizedOriginalFontPath();
        this.victorFamily = TtfBmFontGenerator.isVictorManagedFontPath(faceKey);
        this.fontFiles = resolveFontFiles(spec, fontDir);
        this.calibrationFactors = new float[fontFiles.size()];
        Arrays.fill(calibrationFactors, -1f);
    }

    /**
     * 按 spec.allFontCandidates() 顺序收集存在的字体文件（去重），构成 face 链——
     * 主字体缺字形（如 lte50549 只覆盖 Latin，无 CJK）时按链回退到 fallback
     * 字体栅格化，语义与生产 FontChain 烘焙期的 MiSans 兜底一致。
     * 一个都找不到时抛错带全路径。
     */
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

    // ------------------------------------------------------------------
    // GlyphProvider：度量转发基底 fnt，UV/textureId 来自图集槽位
    // ------------------------------------------------------------------

    @Override
    public GlyphMetrics glyph(final int codePoint) {
        final GlyphMetrics base = metrics.glyph(codePoint);
        if (base == null) {
            return null;
        }
        // '{'/'}' 空格化（原版 fnt 语义上提：零尺寸、advance 继承空格；生成 fnt 已烘焙时幂等）
        if (TtfBmFontGenerator.shouldTreatAsSpaceGlyph(codePoint)) {
            final GlyphMetrics space = metrics.glyph(SPACE_CODE_POINT);
            final float advance = space != null ? space.xAdvance() : base.xAdvance();
            return new GlyphMetrics(0, advance, 0, 0, 0, 0f, 0f, 0f, 0f, 0);
        }
        // 零尺寸/1×1 占位符（TtfBmFontGenerator.preservedSourceMetric 上提）：原版 bake 对这些
        // 码点保留空位图（不产可见像素），TTF 路径等价处理——保留 advance 度量、
        // 尺寸归零（引擎跳过 quad）、不产图集请求（不占槽位、不采到无效的 0 号纹理）
        if (base.width() <= 1 && base.height() <= 1) {
            return new GlyphMetrics(base.xOffset(), base.xAdvance(), base.bearingY(),
                    0, 0, 0f, 0f, 0f, 0f, 0);
        }

        final int rasterCodePoint = victorFamily
                ? TtfBmFontGenerator.substituteVictorLowercaseCodePoint(codePoint)
                : codePoint;
        return cachedSlotMetrics(rasterCodePoint, base, 0);
    }

    @Override
    public GlyphMetrics strokedGlyph(final int codePoint, final float strokeWidthLogicalPx) {
        final GlyphMetrics base = metrics.glyph(codePoint);
        if (base == null || (base.width() <= 1 && base.height() <= 1)) {
            return null;
        }
        final float bucketScale = currentBucketScale();
        // 描边宽度：逻辑像素 → 设备像素按 0.5px 步进量化。缓存 key（千分位）、
        // native 栅格化入参、composeToFontBox 画布外扩种子三处共用同一量化设备值；
        // 引擎剪影 quad 几何不再手工外扩——直接取描边槽位的并集盒度量
        // （slotMetrics 返回的 xOffset/bearingY/width/height），见 TextLayoutEngine
        final int strokeBucketMillis = Math.round(
                TextScaleBuckets.quantizeStrokeDevicePx(strokeWidthLogicalPx, bucketScale) * 1000f);
        final int rasterCodePoint = victorFamily
                ? TtfBmFontGenerator.substituteVictorLowercaseCodePoint(codePoint)
                : codePoint;
        return cachedSlotMetrics(rasterCodePoint, base, strokeBucketMillis);
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
        atlas.flushDirty();
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

    /** 解析到的主字体文件名（face 链首元素，诊断日志用）。 */
    String fontFileName() {
        return fontFiles.get(0).getFileName().toString();
    }

    /**
     * 测试钩子：返回指定字体文件在指定 bucket 下的校准后像素字号
     * （与 {@link #faceAtLocked} 实际建 face 所用字号一致，含运行期校准因子）。
     *
     * @param fontFile     必须是 {@link #fontFiles} 中的条目，否则抛 IllegalArgumentException
     * @param bucketMillis bucket 缩放（千分比，1000 = 1.0）
     */
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

    // ------------------------------------------------------------------
    // CJK 预热
    // ------------------------------------------------------------------

    /**
     * 启动 CJK 常用字预热（daemon 线程，一次性）：遍历基底 fnt 的非空字形，
     * 过滤 ASCII 可打印与 CJK 区间（0x2E80..0x9FFF / 0x3000..0x303F / 0xFF00..0xFFEF），
     * 按当前 bucket 批量栅格化入图集。异常只记日志，不影响游戏。
     */
    public void startWarmup() {
        final Thread thread = new Thread(this::warmupCommonGlyphs,
                "SSOptimizer-FontWarmup-" + faceKey.substring(faceKey.lastIndexOf('/') + 1));
        thread.setDaemon(true);
        thread.start();
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

    /** 预热码点集：ASCII 可打印 + CJK 区间，跳过零尺寸占位符与空格化码点。 */
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

    /** 一批码点经后端按 face 链分组批量栅格化后逐字入图集（face 访问与逻辑线程互斥）。 */
    private int warmupBatch(final List<Integer> batch, final int bucketMillis) {
        // 度量按原码点查（与 glyph() 路径一致），图集 key 用 victor 替换后的码点
        final int[] rasterCodePoints = new int[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            final int cp = batch.get(i);
            rasterCodePoints[i] = victorFamily ? TtfBmFontGenerator.substituteVictorLowercaseCodePoint(cp) : cp;
        }
        final int baseline = baselineForBucket(bucketMillis);
        // 逐码点选定链上第一个含该字形的 face（主字体无 CJK 时落到 fallback face），
        // 同 face 的码点聚合成一次批量栅格化以摊薄 JNI 边界
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
            final NativeGlyphBitmap composed = composeToFontBox(bitmap, base, 0, bucketMillis / 1000.0f);
            final GlyphSlot slot = atlas.request(faceKey, bucketMillis, 0, rasterCodePoints[i], baseline,
                    (cp, bl, stroke) -> composed);
            if (slot != null) {
                stored++;
            }
        }
        return stored;
    }

    // ------------------------------------------------------------------
    // 内部：face 缓存、槽位请求、fnt 盒对齐合成
    // ------------------------------------------------------------------

    /**
     * 槽位度量直通缓存路径（见 slotCache 字段注释）：命中直接返回缓存度量，
     * 未命中穿透到 {@link #requestSlot} 并按代际守卫入缓存（null 与 textureId==0
     * 的窗口期结果不缓存）。
     */
    private GlyphMetrics cachedSlotMetrics(final int rasterCodePoint,
                                           final GlyphMetrics base,
                                           final int strokeBucketMillis) {
        final int generation = atlas.textureGeneration();
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

    /**
     * 请求图集槽位：把 native 墨迹位图合成到并集画布（fnt 盒 × bucketScale ∪ 墨迹
     * 包围盒，描边档以「量化后的描边设备像素」为外扩种子）后入图集。
     * 槽位度量（含画布原点）经 {@link #slotMetrics} 折回逻辑坐标后驱动 quad 几何，
     * 引擎侧无需再手工外扩剪影 quad。
     * 栅格化 face 在 miss 回调内按链惰性选择（主 face 起第一个含该字形的 face），
     * 命中路径零 hasGlyph 查询开销。
     */
    private GlyphSlot requestSlot(final int rasterCodePoint,
                                  final GlyphMetrics base,
                                  final int strokeBucketMillis) {
        final int baseline = baselineForBucket(currentBucketMillis);
        final float bucketScale = currentBucketScale();
        synchronized (faces) {
            final long[] chain = faceChainForBucketLocked(currentBucketMillis);
            return atlas.request(faceKey, currentBucketMillis, strokeBucketMillis, rasterCodePoint, baseline,
                    (cp, bl, stroke) -> {
                        final long face = faceForGlyphLocked(chain, currentBucketMillis, cp);
                        if (face == 0L) {
                            return null;
                        }
                        final NativeGlyphBitmap bitmap = backend.rasterize(face, cp, bl, stroke);
                        return bitmap == null ? null : composeToFontBox(bitmap, base, strokeBucketMillis, bucketScale);
                    });
        }
    }

    /**
     * face 链按 bucket 惰性初始化（pixelSize = 名义字号 × bucketScale，逐文件
     * 在首次需要时创建）；数组元素 -1 = 尚未创建，创建失败保持 -1 下次重试。
     */
    private long[] faceChainForBucketLocked(final int bucketMillis) {
        long[] chain = faces.get(bucketMillis);
        if (chain == null) {
            chain = new long[fontFiles.size()];
            Arrays.fill(chain, -1L);
            faces.put(bucketMillis, chain);
        }
        return chain;
    }

    /** 链上第 index 个文件的 face（惰性创建；创建失败返回 0 且不留缓存，下次重试）。 */
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

    /**
     * 运行期 face 校准因子（每字体文件惰性计算一次，faces 锁内）。
     * <p>
     * 生成期（{@link TtfBmFontGenerator}）会把 TTF 缩放到烘焙 fnt 的排版密度，运行期
     * 若直接用名义字号建 face，墨迹会大于烘焙度量——CJK 尤为明显（观感 = 字距过挤、
     * 相邻字形边缘互叠）。本方法与生成期校准逐语义对齐：
     * <ul>
     *   <li>链首（主字体，Latin 样本）：因子 = fnt 主样本平均步进 / native 主样本
     *       平均步进，钳制 [0.88, 1.08]（对应 harmonizePrimaryAdvance）；</li>
     *   <li>其余（fallback，CJK 样本）：目标步进 = 主字体校准后渲染平均步进 ×
     *       (fnt fallback 样本平均步进 / fnt 主样本平均步进)，因子 = 目标 / native
     *       fallback 平均步进，钳制 [0.70, 1.36]（对应 harmonizeFallbackMetrics）——
     *       注意目标不是「CJK 步进 = fnt CJK 步进」：那样方形 CJK 墨迹会撑满整个
     *       advance 盒、侧方位为零，正文观感过挤。</li>
     * </ul>
     * 探针不可用（face 创建失败 / 无覆盖 / 步进无效）时因子 1.0 并记日志。
     */
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

    /** 主字体校准：fnt 主样本平均步进 / native 主样本平均步进，钳制到主字体区间。 */
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

    /**
     * fallback 校准：目标步进 = 主字体校准后渲染平均步进 × (fnt CJK 样本平均步进 /
     * fnt 主样本平均步进)（与生成期 harmonizeFallbackMetrics 同式），因子 =
     * 目标 / native CJK 样本平均步进，钳制到 fallback 区间。主字体探针无效时按 1.0。
     */
    private float fallbackCalibrationFactorLocked(final long probe) {
        final float fntPrimaryAvg = averageFntAdvance(TtfBmFontGenerator.PRIMARY_ADVANCE_SAMPLE);
        final float fntFallbackAvg = averageFntAdvance(TtfBmFontGenerator.FALLBACK_VISUAL_SAMPLE);
        final float nativeFallbackAvg = averageProbeAdvance(probe, TtfBmFontGenerator.FALLBACK_VISUAL_SAMPLE);
        // 主字体「校准后」的渲染平均步进：名义 × 主字体因子 建临时探针 face 量取
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

    /** 采样串在 fnt 度量上的平均步进（无字形/非正步进的码点跳过；无有效样本返回 0）。 */
    private float averageFntAdvance(final String sample) {
        float sum = 0f;
        int count = 0;
        for (final int cp : sample.codePoints().toArray()) {
            final GlyphMetrics gm = metrics.glyph(cp);
            if (gm == null || gm.xAdvance() <= 0f) {
                continue;
            }
            sum += gm.xAdvance();
            count++;
        }
        return count == 0 ? 0f : sum / count;
    }

    /** 采样串在指定 face 上的平均 native 步进（无覆盖/非正步进的码点跳过；无有效样本返回 0）。 */
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

    /**
     * 从主 face 起按链找第一个含 codePoint 字形的 face（CJK fallback：
     * 主字体只覆盖 Latin 时落到 MiSans 等 fallback 字体）；链上全无返回 0
     * （调用方按缺失字形语义回退 '?'）。
     */
    private long faceForGlyphLocked(final long[] chain, final int bucketMillis, final int codePoint) {
        for (int i = 0; i < chain.length; i++) {
            final long face = faceAtLocked(chain, i, bucketMillis);
            if (face != 0L && backend.hasGlyph(face, codePoint)) {
                return face;
            }
        }
        return 0L;
    }

    /** 基线（face 像素坐标系）= fnt base × bucketScale。 */
    private int baselineForBucket(final int bucketMillis) {
        return Math.round(baseFont.getBase() * (bucketMillis / 1000.0f));
    }

    /**
     * 把 native 墨迹位图按偏移合成进「并集画布」：画布 = 「fnt 盒 × bucketScale
     * （描边档四向外扩量化后的描边设备像素）」与墨迹实际包围盒的并集。
     * <p>
     * 动机：FreeType hinting 在非整数缩放下会让墨迹超出缩放 fnt 盒 1-2 设备像素
     * （如 22.5px 的 CJK 字比 round(13×1.5) 宽 1px），硬裁剪会丢失边缘笔画——
     * 并集画布保证零裁剪，原点/尺寸经 {@link GlyphSlot#originX()}/{@link GlyphSlot#originY()}
     * 与 float 度量传导给 quad 几何，亚像素精度不丢失。
     * <p>
     * 墨迹落点 = native 偏移 − 画布原点（同坐标系：x 相对落笔点、y 相对行顶——
     * fnt 的 bearingY 是游戏解析器从 yoffset 原样装入的「行顶到盒顶」距离，
     * 与 native yOffset = baseline − bitmap_top 同坐标系，见 AtlasSoftwareRenderIT）。
     *
     * @param strokeBucketMillis 量化后的描边宽度（设备像素千分位，0 = 纯填充）
     * @return 合成位图；xOffset/yOffset 携带画布原点（笔坐标系/行顶坐标系设备像素）
     */
    NativeGlyphBitmap composeToFontBox(final NativeGlyphBitmap ink,
                                       final GlyphMetrics base,
                                       final int strokeBucketMillis,
                                       final float bucketScale) {
        final int expand = Math.round(strokeBucketMillis / 1000f);
        final int fntLeft = Math.round(base.xOffset() * bucketScale) - expand;
        final int fntTop = Math.round(base.bearingY() * bucketScale) - expand;
        final int fntWidth = Math.max(1, Math.round(base.width() * bucketScale) + expand * 2);
        final int fntHeight = Math.max(1, Math.round(base.height() * bucketScale) + expand * 2);

        int left = fntLeft;
        int top = fntTop;
        int right = fntLeft + fntWidth;
        int bottom = fntTop + fntHeight;
        if (ink.hasImage()) {
            left = Math.min(left, ink.xOffset());
            top = Math.min(top, ink.yOffset());
            right = Math.max(right, ink.xOffset() + ink.width());
            bottom = Math.max(bottom, ink.yOffset() + ink.height());
        }
        final int canvasWidth = right - left;
        final int canvasHeight = bottom - top;

        final int[] canvas = new int[canvasWidth * canvasHeight];
        if (ink.hasImage()) {
            // 并集构造保证墨迹必然完整落入画布，无裁剪分支
            final int destX = ink.xOffset() - left;
            final int destY = ink.yOffset() - top;
            final int[] pixels = ink.argbPixels();
            for (int row = 0; row < ink.height(); row++) {
                System.arraycopy(pixels, row * ink.width(),
                        canvas, (destY + row) * canvasWidth + destX, ink.width());
            }
        }
        return new NativeGlyphBitmap(canvasWidth, canvasHeight, canvas, left, top, 0);
    }

    /**
     * 槽位 → GlyphMetrics：盒度量取「并集画布原点/尺寸 ÷ bucketScale」的逻辑值
     * （亚像素），xAdvance 补偿左溢出以保持 xOffset+xAdvance 步进和与 fnt 原值
     * 一致（行宽/折行/光标语义不变）；UV/textureId 取槽位。
     */
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

    // ------------------------------------------------------------------
    // 栅格后端 seam（生产 = NativeFontRasterizer 门面；测试 = 内存假实现）
    // ------------------------------------------------------------------

    /**
     * TTF 栅格后端：把 provider 与 native 门面解耦（单测注入假实现）。
     * 实现线程安全由调用方保证（全部调用在 faces 锁内）。
     */
    interface TtfRasterBackend {
        /** 创建 face（pixelSize 目标像素尺寸，固定开 AA）；失败返回 0。 */
        long createFace(Path fontFile, float pixelSize);

        /** face 是否含 codePoint 的字形（face 链回退选择用；码点 0 恒 false）。 */
        boolean hasGlyph(long face, int codePoint);

        /**
         * 校准探针：face 在其创建尺寸下 codePoint 的原生步进（像素）。
         * 仅用于运行期校准因子计算（见 calibrationFactorLocked）；≤0 = 不可用，
         * 调用方按因子 1.0 处理并记日志。
         */
        float probeAdvance(long face, int codePoint);

        /** 栅格化单字形；strokeWidthPx &gt; 0 时返回「填充 ∪ 外扩剪影」合成位图。 */
        NativeGlyphBitmap rasterize(long face, int codePoint, int baseline, float strokeWidthPx);

        /** 批量栅格化（预热摊薄 JNI 边界）；失败返回 null。 */
        NativeGlyphBitmap[] rasterizeBatch(long face, int[] codePoints, int baseline, float strokeWidthPx);

        /** 销毁 face。 */
        void destroyFace(long face);
    }

    /** 生产后端：转发 {@link NativeFontRasterizer}（含 P3 新增的描边/批量接口）。 */
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
            // 步进与基线无关，baseline 传 0；复用既有栅格化接口，一次性探针开销可忽略
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
