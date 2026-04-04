package github.kasuminova.ssoptimizer.common.font;

import org.apache.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphMetrics;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.List;

/**
 * TrueType 字体位图生成器。
 * <p>
 * 将 TTF/OTF 字体文件栅格化为 Starsector 使用的 {@code .fnt + .png} 位图字体格式，
 * 支持高 DPI 缩放和完整 CJK 字符集。
 */
final class TtfBmFontGenerator {
    private static final Logger LOGGER                          = Logger.getLogger(TtfBmFontGenerator.class);
    private static final int    SPACE_CODE_POINT                = ' ';
    private static final int    LEFT_BRACE_CODE_POINT           = '{';
    private static final int    RIGHT_BRACE_CODE_POINT          = '}';
    private static final String PRIMARY_VISUAL_SAMPLE           = "HNM0";
    private static final String PRIMARY_ADVANCE_SAMPLE          = "HNM0UI";
    private static final String FALLBACK_VISUAL_SAMPLE          = "汉界测港";
    private static final String VICTOR_PRIMARY_SAMPLE           = "ABMWQJ";
    private static final int    MAX_RUNTIME_PAGE_DIMENSION      = 8192;
    private static final int    MIN_RUNTIME_PAGE_STEP           = 64;
    private static final int    MAX_RUNTIME_PAGE_STEP           = 512;
    private static final float  MIN_PRIMARY_ADVANCE_SCALE       = 0.88f;
    private static final float  MAX_PRIMARY_ADVANCE_SCALE       = 1.08f;
    private static final float  MIN_FALLBACK_VISUAL_SCALE       = 0.88f;
    private static final float  MAX_FALLBACK_VISUAL_SCALE       = 1.36f;
    private static final float  MIN_VICTOR_PRIMARY_VISUAL_SCALE = 0.94f;
    private static final float  MAX_VICTOR_PRIMARY_VISUAL_SCALE = 1.24f;

    private TtfBmFontGenerator() {
    }

    static GeneratedFontPack generate(final OriginalGameFontOverrides.FontOverrideSpec spec,
                                      final Path fontDir) throws IOException, FontFormatException {
        return loadOrGenerateCached(spec, spec.originalFontPath(), fontDir, 1.0f, () -> {
            final SourceBmFont source = SourceBmFont
                    .parse(OriginalGameFontOverrides.resolveOriginalFontFile(spec.originalFontPath()));
            final FontChain fonts = loadFontChain(spec, fontDir, source);
            final RasterizedGlyphs rasterized = rasterizeGlyphs(source, fonts);
            final FittedAtlas fitted = fitSinglePageAtlas(spec, spec.originalFontPath(), source, rasterized, 1.0f);
            return buildPack(fitted.spec(), source, fonts, fitted.layout());
        });
    }

    static GeneratedFontPack generateScaled(final OriginalGameFontOverrides.FontOverrideSpec baseSpec,
                                            final Path fontDir,
                                            final String runtimeFontPath,
                                            final float scale) throws IOException, FontFormatException {
        final SourceBmFont source = SourceBmFont
                .parse(OriginalGameFontOverrides.resolveOriginalFontFile(baseSpec.originalFontPath()));
        final OriginalGameFontOverrides.FontOverrideSpec runtimeSpec = runtimeSpecForScale(
                baseSpec,
                runtimeFontPath,
                scale,
                source.scaleWidth(),
                source.scaleHeight());
        return loadOrGenerateCached(runtimeSpec, baseSpec.originalFontPath(), fontDir, scale, () -> {
            final SourceBmFont scaledSource = source.scaled(scale);
            final FontChain fonts = loadFontChain(runtimeSpec, fontDir, scaledSource);
            final RasterizedGlyphs rasterized = rasterizeGlyphs(scaledSource, fonts);
            final FittedAtlas fitted = fitSinglePageAtlas(baseSpec, runtimeFontPath, scaledSource, rasterized, scale);
            return buildPack(fitted.spec(), scaledSource, fonts, fitted.layout());
        });
    }

    static OriginalGameFontOverrides.FontOverrideSpec runtimeSpecForScale(
            final OriginalGameFontOverrides.FontOverrideSpec baseSpec,
            final String runtimeFontPath,
            final float scale) {
        return runtimeSpecForScale(baseSpec, runtimeFontPath, scale, baseSpec.pageWidth(), baseSpec.pageHeight());
    }

    static OriginalGameFontOverrides.FontOverrideSpec runtimeSpecForScale(
            final OriginalGameFontOverrides.FontOverrideSpec baseSpec,
            final String runtimeFontPath,
            final float scale,
            final int sourcePageWidth,
            final int sourcePageHeight) {
        return new OriginalGameFontOverrides.FontOverrideSpec(
                runtimeFontPath,
                baseSpec.primaryFontCandidates(),
                baseSpec.fallbackFontCandidates(),
                runtimePageDimension(preferredPageDimension(baseSpec.pageWidth(), sourcePageWidth), scale),
                runtimePageDimension(preferredPageDimension(baseSpec.pageHeight(), sourcePageHeight), scale));
    }

    static int preferredPageDimension(final int configuredDimension,
                                      final int sourceDimension) {
        final int normalizedConfiguredDimension = Math.max(1, configuredDimension);
        return sourceDimension > 0 ? sourceDimension : normalizedConfiguredDimension;
    }

    static int runtimePageDimension(final int baseDimension,
                                    final float scale) {
        final int normalizedBaseDimension = Math.max(1, baseDimension);
        if (!Float.isFinite(scale) || scale <= 1.0f) {
            return normalizedBaseDimension;
        }

        final int step = runtimePageStep(normalizedBaseDimension);
        final long minimumRequiredDimension = Math.max(
                normalizedBaseDimension,
                Math.round(normalizedBaseDimension * scale));
        final long cappedRequiredDimension = Math.min(MAX_RUNTIME_PAGE_DIMENSION, minimumRequiredDimension);
        return Math.max(normalizedBaseDimension, roundUpToStep((int) cappedRequiredDimension, step));
    }

    static int runtimePageStep(final int baseDimension) {
        final int normalizedBaseDimension = Math.max(1, baseDimension);
        final int rawStep = Math.max(MIN_RUNTIME_PAGE_STEP, normalizedBaseDimension / 8);
        return Math.min(MAX_RUNTIME_PAGE_STEP, roundUpToStep(rawStep, MIN_RUNTIME_PAGE_STEP));
    }

    private static int roundUpToStep(final int value,
                                     final int step) {
        final int normalizedValue = Math.max(1, value);
        final int normalizedStep = Math.max(1, step);
        final long rounded = ((long) normalizedValue + normalizedStep - 1L) / normalizedStep * normalizedStep;
        return (int) Math.min(Integer.MAX_VALUE, rounded);
    }

    static int readNominalFontSize(final String originalFontPath) throws IOException {
        return Math.max(1, Math.abs(
                SourceBmFont.parse(OriginalGameFontOverrides.resolveOriginalFontFile(originalFontPath)).infoSize()));
    }

    private static GeneratedFontPack loadOrGenerateCached(final OriginalGameFontOverrides.FontOverrideSpec spec,
                                                          final String sourceFontPath,
                                                          final Path fontDir,
                                                          final float scale,
                                                          final FontPackFactory factory) throws IOException, FontFormatException {
        final String cacheKey = FontPackCache.buildCacheKey(spec, sourceFontPath, fontDir, scale);
        final GeneratedFontPack cached = FontPackCache.load(cacheKey, spec);
        if (cached != null) {
            return cached;
        }

        final GeneratedFontPack generated = factory.generate();
        FontPackCache.store(cacheKey, spec, generated);
        return generated;
    }

    private static FontChain loadFontChain(final OriginalGameFontOverrides.FontOverrideSpec spec,
                                           final Path fontDir,
                                           final SourceBmFont source) throws IOException, FontFormatException {
        final float requestedSize = Math.max(1f, Math.abs(source.infoSize()));
        final FontRenderPolicy renderPolicy = renderPolicyForSpec(spec, source);
        final boolean victorManaged = isVictorManagedFontPath(spec.originalFontPath());
        final boolean antiAlias = renderPolicy.antiAlias();
        final boolean fractionalMetrics = renderPolicy.fractionalMetrics();
        final List<LoadedFont> primary = new ArrayList<>();
        for (String candidate : spec.primaryFontCandidates()) {
            final Path path = spec.resolveCandidate(fontDir, candidate);
            if (Files.isRegularFile(path)) {
                Font calibrated = harmonizePrimaryAdvance(
                        calibrate(loadFont(path), requestedSize, source.lineHeight(), antiAlias, fractionalMetrics),
                        source,
                        antiAlias,
                        fractionalMetrics);
                if (victorManaged) {
                    calibrated = harmonizeVictorPrimaryVisual(calibrated, source, antiAlias, fractionalMetrics);
                }
                primary.add(new LoadedFont(
                        path.getFileName().toString(),
                        path,
                        calibrated));
                break;
            }
        }
        if (primary.isEmpty()) {
            throw new IOException("No primary font candidate found for " + spec.originalFontPath() + " in " + fontDir);
        }

        final Font primaryFont = primary.getFirst().font();
        final float primaryVisualHeight = measureVisualHeight(primaryFont, PRIMARY_VISUAL_SAMPLE, antiAlias,
                fractionalMetrics);
        final float primaryAverageHeight = measureAverageHeight(primaryFont, PRIMARY_VISUAL_SAMPLE, antiAlias,
                fractionalMetrics);
        final float primaryAverageAdvance = measureAverageAdvance(primaryFont, PRIMARY_ADVANCE_SAMPLE, antiAlias,
                fractionalMetrics);

        final List<LoadedFont> fallback = new ArrayList<>();
        for (String candidate : spec.fallbackFontCandidates()) {
            final Path path = spec.resolveCandidate(fontDir, candidate);
            if (Files.isRegularFile(path)) {
                final Font calibrated = calibrate(loadFont(path), requestedSize, source.lineHeight(), antiAlias,
                        fractionalMetrics);
                fallback.add(new LoadedFont(
                        path.getFileName().toString(),
                        path,
                        harmonizeFallbackMetrics(calibrated, source, primaryVisualHeight, primaryAverageHeight,
                                primaryAverageAdvance, antiAlias, fractionalMetrics)));
            }
        }
        final Font dialogFont = calibrate(new Font(Font.DIALOG, Font.PLAIN, Math.round(requestedSize)), requestedSize,
                source.lineHeight(), antiAlias, fractionalMetrics);
        fallback.add(new LoadedFont(
                "Dialog",
                null,
                harmonizeFallbackMetrics(dialogFont, source, primaryVisualHeight, primaryAverageHeight,
                        primaryAverageAdvance, antiAlias, fractionalMetrics)));

        final List<LoadedFont> chain = new ArrayList<>(primary.size() + fallback.size());
        chain.addAll(primary);
        chain.addAll(fallback);
        return new FontChain(chain, antiAlias, fractionalMetrics, renderPolicy.pixelFont(), victorManaged);
    }

    private static Font loadFont(final Path path) throws IOException, FontFormatException {
        try (InputStream in = Files.newInputStream(path)) {
            return Font.createFont(Font.TRUETYPE_FONT, in);
        }
    }

    private static Font calibrate(final Font base,
                                  final float requestedSize,
                                  final int targetLineHeight,
                                  final boolean antiAlias,
                                  final boolean fractionalMetrics) {
        final BufferedImage scratch = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = scratch.createGraphics();
        try {
            applyTextHints(g, antiAlias, fractionalMetrics);
            Font derived = base.deriveFont(requestedSize);
            final FontRenderContext frc = g.getFontRenderContext();
            final LineMetrics metrics = derived.getLineMetrics("Hg", frc);
            final float measured = Math.max(1f, metrics.getAscent() + metrics.getDescent() + metrics.getLeading());
            final float scale = targetLineHeight / measured;
            return base.deriveFont(Math.max(1f, requestedSize * scale));
        } finally {
            g.dispose();
        }
    }

    private static Font harmonizeFallbackMetrics(final Font calibrated,
                                                 final SourceBmFont source,
                                                 final float primaryVisualHeight,
                                                 final float primaryAverageHeight,
                                                 final float primaryAverageAdvance,
                                                 final boolean antiAlias,
                                                 final boolean fractionalMetrics) {
        if (!canDisplayAny(calibrated, FALLBACK_VISUAL_SAMPLE)) {
            return calibrated;
        }

        final List<Float> scaleFactors = new ArrayList<>(3);

        final float sourcePrimaryAdvance = source.averageAdvance(PRIMARY_ADVANCE_SAMPLE);
        final float sourceAverageAdvance = source.averageAdvance(FALLBACK_VISUAL_SAMPLE);
        final float renderedAverageAdvance = measureAverageAdvance(calibrated, FALLBACK_VISUAL_SAMPLE, antiAlias,
                fractionalMetrics);
        final float targetAverageAdvance = fallbackTargetMetric(primaryAverageAdvance, sourcePrimaryAdvance,
                sourceAverageAdvance);
        if (targetAverageAdvance > 0f && renderedAverageAdvance > 0f) {
            scaleFactors.add(fallbackVisualScaleFactor(targetAverageAdvance, renderedAverageAdvance));
        }

        final float sourcePrimaryHeight = source.averageHeight(PRIMARY_VISUAL_SAMPLE);
        final float sourceAverageHeight = source.averageHeight(FALLBACK_VISUAL_SAMPLE);
        final float renderedAverageHeight = measureAverageHeight(calibrated, FALLBACK_VISUAL_SAMPLE, antiAlias,
                fractionalMetrics);
        final float targetAverageHeight = fallbackTargetMetric(primaryAverageHeight, sourcePrimaryHeight,
                sourceAverageHeight);
        if (targetAverageHeight > 0f && renderedAverageHeight > 0f) {
            scaleFactors.add(fallbackVisualScaleFactor(targetAverageHeight, renderedAverageHeight));
        }

        if (scaleFactors.isEmpty() && primaryVisualHeight > 0f) {
            final float fallbackVisualHeight = measureVisualHeight(calibrated, FALLBACK_VISUAL_SAMPLE, antiAlias,
                    fractionalMetrics);
            final float primaryVisualScale = fallbackVisualScaleFactor(primaryVisualHeight, fallbackVisualHeight);
            if (primaryVisualScale > 0f) {
                scaleFactors.add(primaryVisualScale);
            }
        }

        if (scaleFactors.isEmpty()) {
            return calibrated;
        }

        float scaleFactor = 0f;
        for (Float candidateScale : scaleFactors) {
            scaleFactor += candidateScale;
        }
        scaleFactor /= scaleFactors.size();
        if (Math.abs(scaleFactor - 1.0f) < 0.02f) {
            return calibrated;
        }
        return calibrated.deriveFont(Math.max(1f, calibrated.getSize2D() * scaleFactor));
    }

    static float fallbackTargetMetric(final float primaryRenderedMetric,
                                      final float sourcePrimaryMetric,
                                      final float sourceFallbackMetric) {
        if (!Float.isFinite(primaryRenderedMetric)
                || !Float.isFinite(sourcePrimaryMetric)
                || !Float.isFinite(sourceFallbackMetric)
                || primaryRenderedMetric <= 0f
                || sourcePrimaryMetric <= 0f
                || sourceFallbackMetric <= 0f) {
            return 0f;
        }
        return primaryRenderedMetric * (sourceFallbackMetric / sourcePrimaryMetric);
    }

    static float fallbackVisualScaleFactor(final float primaryVisualHeight,
                                           final float fallbackVisualHeight) {
        if (!Float.isFinite(primaryVisualHeight) || !Float.isFinite(fallbackVisualHeight)
                || primaryVisualHeight <= 0f || fallbackVisualHeight <= 0f) {
            return 1.0f;
        }

        final float rawScale = primaryVisualHeight / fallbackVisualHeight;
        if (!Float.isFinite(rawScale) || rawScale <= 0f) {
            return 1.0f;
        }
        return Math.max(MIN_FALLBACK_VISUAL_SCALE, Math.min(MAX_FALLBACK_VISUAL_SCALE, rawScale));
    }

    static float primaryAdvanceScaleFactor(final float sourceAverageAdvance,
                                           final float renderedAverageAdvance) {
        if (!Float.isFinite(sourceAverageAdvance) || !Float.isFinite(renderedAverageAdvance)
                || sourceAverageAdvance <= 0f || renderedAverageAdvance <= 0f) {
            return 1.0f;
        }

        final float rawScale = sourceAverageAdvance / renderedAverageAdvance;
        if (!Float.isFinite(rawScale) || rawScale <= 0f) {
            return 1.0f;
        }
        return Math.max(MIN_PRIMARY_ADVANCE_SCALE, Math.min(MAX_PRIMARY_ADVANCE_SCALE, rawScale));
    }

    private static boolean canDisplayAny(final Font font,
                                         final String sample) {
        for (int index = 0; index < sample.length(); ) {
            final int codePoint = sample.codePointAt(index);
            if (font.canDisplay(codePoint)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static Font harmonizePrimaryAdvance(final Font calibrated,
                                                final SourceBmFont source,
                                                final boolean antiAlias,
                                                final boolean fractionalMetrics) {
        final float sourceAverageAdvance = source.averageAdvance(PRIMARY_ADVANCE_SAMPLE);
        if (sourceAverageAdvance <= 0f) {
            return calibrated;
        }

        final float renderedAverageAdvance = measureAverageAdvance(calibrated, PRIMARY_ADVANCE_SAMPLE, antiAlias,
                fractionalMetrics);
        final float scaleFactor = primaryAdvanceScaleFactor(sourceAverageAdvance, renderedAverageAdvance);
        if (Math.abs(scaleFactor - 1.0f) < 0.02f) {
            return calibrated;
        }
        return calibrated.deriveFont(Math.max(1f, calibrated.getSize2D() * scaleFactor));
    }

    private static Font harmonizeVictorPrimaryVisual(final Font calibrated,
                                                     final SourceBmFont source,
                                                     final boolean antiAlias,
                                                     final boolean fractionalMetrics) {
        final float sourceAverageHeight = source.averageHeight(VICTOR_PRIMARY_SAMPLE);
        if (sourceAverageHeight <= 0f) {
            return calibrated;
        }

        final float renderedAverageHeight = measureAverageHeight(calibrated, VICTOR_PRIMARY_SAMPLE, antiAlias,
                fractionalMetrics);
        final float scaleFactor = victorPrimaryVisualScaleFactor(sourceAverageHeight, renderedAverageHeight);
        if (Math.abs(scaleFactor - 1.0f) < 0.02f) {
            return calibrated;
        }
        return calibrated.deriveFont(Math.max(1f, calibrated.getSize2D() * scaleFactor));
    }

    static float victorPrimaryVisualScaleFactor(final float sourceAverageHeight,
                                                final float renderedAverageHeight) {
        if (!Float.isFinite(sourceAverageHeight) || !Float.isFinite(renderedAverageHeight)
                || sourceAverageHeight <= 0f || renderedAverageHeight <= 0f) {
            return 1.0f;
        }

        final float rawScale = sourceAverageHeight / renderedAverageHeight;
        if (!Float.isFinite(rawScale) || rawScale <= 0f) {
            return 1.0f;
        }
        return Math.max(MIN_VICTOR_PRIMARY_VISUAL_SCALE,
                Math.min(MAX_VICTOR_PRIMARY_VISUAL_SCALE, rawScale));
    }

    static boolean isVictorManagedFontPath(final String fontPath) {
        final String normalized = OriginalGameFontOverrides.normalize(fontPath).toLowerCase(Locale.ROOT);
        return normalized.startsWith("graphics/fonts/victor10")
                || normalized.startsWith("graphics/fonts/victor14")
                || normalized.startsWith("ssoptimizer/runtimefonts/graphics/fonts/victor10")
                || normalized.startsWith("ssoptimizer/runtimefonts/graphics/fonts/victor14");
    }

    static int substituteVictorLowercaseCodePoint(final int codePoint) {
        if (codePoint >= 'a' && codePoint <= 'z') {
            return Character.toUpperCase(codePoint);
        }
        return codePoint;
    }

    private static int remappedRasterCodePoint(final int codePoint,
                                               final FontChain fonts) {
        if (!fonts.substituteLowercaseWithUppercase()) {
            return codePoint;
        }
        return substituteVictorLowercaseCodePoint(codePoint);
    }

    private static GlyphRaster withCodePoint(final GlyphRaster rasterized,
                                             final int codePoint) {
        if (rasterized == null || rasterized.codePoint() == codePoint) {
            return rasterized;
        }
        return new GlyphRaster(
                codePoint,
                rasterized.sourceName(),
                rasterized.faceName(),
                rasterized.image(),
                rasterized.width(),
                rasterized.height(),
                rasterized.xOffset(),
                rasterized.yOffset(),
                rasterized.xAdvance());
    }

    private static float measureVisualHeight(final Font font,
                                             final String sample,
                                             final boolean antiAlias,
                                             final boolean fractionalMetrics) {
        final BufferedImage scratch = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = scratch.createGraphics();
        try {
            applyTextHints(g, antiAlias, fractionalMetrics);
            final FontRenderContext frc = g.getFontRenderContext();
            final GlyphVector gv = font.createGlyphVector(frc, sample);
            final java.awt.Rectangle bounds = gv.getPixelBounds(frc, 0f, 0f);
            return Math.max(0f, bounds.height);
        } finally {
            g.dispose();
        }
    }

    private static float measureAverageAdvance(final Font font,
                                               final String sample,
                                               final boolean antiAlias,
                                               final boolean fractionalMetrics) {
        final BufferedImage scratch = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = scratch.createGraphics();
        try {
            applyTextHints(g, antiAlias, fractionalMetrics);
            final FontRenderContext frc = g.getFontRenderContext();
            int glyphCount = 0;
            float totalAdvance = 0f;
            for (int index = 0; index < sample.length(); ) {
                final int codePoint = sample.codePointAt(index);
                if (font.canDisplay(codePoint)) {
                    final GlyphVector gv = font.createGlyphVector(frc, new String(Character.toChars(codePoint)));
                    totalAdvance += gv.getGlyphMetrics(0).getAdvance();
                    glyphCount++;
                }
                index += Character.charCount(codePoint);
            }
            return glyphCount == 0 ? 0f : totalAdvance / glyphCount;
        } finally {
            g.dispose();
        }
    }

    private static float measureAverageHeight(final Font font,
                                              final String sample,
                                              final boolean antiAlias,
                                              final boolean fractionalMetrics) {
        final BufferedImage scratch = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = scratch.createGraphics();
        try {
            applyTextHints(g, antiAlias, fractionalMetrics);
            final FontRenderContext frc = g.getFontRenderContext();
            int glyphCount = 0;
            float totalHeight = 0f;
            for (int index = 0; index < sample.length(); ) {
                final int codePoint = sample.codePointAt(index);
                if (font.canDisplay(codePoint)) {
                    final GlyphVector gv = font.createGlyphVector(frc, new String(Character.toChars(codePoint)));
                    totalHeight += Math.max(0f, gv.getPixelBounds(frc, 0f, 0f).height);
                    glyphCount++;
                }
                index += Character.charCount(codePoint);
            }
            return glyphCount == 0 ? 0f : totalHeight / glyphCount;
        } finally {
            g.dispose();
        }
    }

    private static RasterizedGlyphs rasterizeGlyphs(final SourceBmFont source,
                                                    final FontChain fonts) {
        final List<GlyphRaster> glyphs = new ArrayList<>(source.codePoints().size());
        final LoadedFont placeholderMetadataFont = fonts.fonts().isEmpty() ? null : fonts.fonts().getFirst();
        final SourceGlyphMetric spaceMetric = source.glyphMetrics().get(SPACE_CODE_POINT);
        try (GlyphRasterizer rasterizer = createRasterizer()) {
            for (int codePoint : source.codePoints()) {
                final SourceGlyphMetric sourceMetric = source.glyphMetrics().get(codePoint);
                final GlyphRaster preserved = preserveSourceSpecialGlyph(codePoint, sourceMetric, spaceMetric,
                        placeholderMetadataFont);
                if (preserved != null) {
                    glyphs.add(preserved);
                    continue;
                }

                final int rasterCodePoint = remappedRasterCodePoint(codePoint, fonts);
                final GlyphRaster rasterized = withCodePoint(
                        rasterizer.rasterizeGlyph(rasterCodePoint, source.base(), fonts),
                        codePoint
                );
                glyphs.add(reconcileRasterizedGlyphToSourceMetrics(rasterized, sourceMetric));
            }
            glyphs.sort(Comparator.comparingInt(GlyphRaster::sortHeight).reversed()
                                  .thenComparingInt(GlyphRaster::codePoint));

            return new RasterizedGlyphs(glyphs, rasterizer.backendName(), rasterizer.backendDetails(fonts));
        }
    }

    private static GlyphRaster preserveSourceSpecialGlyph(final int codePoint,
                                                          final SourceGlyphMetric sourceMetric,
                                                          final SourceGlyphMetric spaceMetric,
                                                          final LoadedFont placeholderMetadataFont) {
        final SourceGlyphMetric preservedMetric = preservedSourceMetric(codePoint, sourceMetric, spaceMetric);
        if (preservedMetric == null) {
            return null;
        }

        final String sourceName = placeholderMetadataFont == null ? "source-placeholder"
                : placeholderMetadataFont.sourceName();
        final String faceName = placeholderMetadataFont == null ? "" : placeholderMetadataFont.faceName();
        return new GlyphRaster(
                codePoint,
                sourceName,
                faceName,
                null,
                preservedMetric.width(),
                preservedMetric.height(),
                preservedMetric.xOffset(),
                preservedMetric.yOffset(),
                preservedMetric.xAdvance());
    }

    private static SourceGlyphMetric preservedSourceMetric(final int codePoint,
                                                           final SourceGlyphMetric sourceMetric,
                                                           final SourceGlyphMetric spaceMetric) {
        if (shouldTreatAsSpaceGlyph(codePoint)) {
            return spaceEquivalentSourceMetric(spaceMetric);
        }
        if (sourceMetric == null || !sourceMetric.isSpecialPlaceholder()) {
            return null;
        }
        return sourceMetric;
    }

    static boolean shouldTreatAsSpaceGlyph(final int codePoint) {
        return codePoint == LEFT_BRACE_CODE_POINT || codePoint == RIGHT_BRACE_CODE_POINT;
    }

    private static SourceGlyphMetric spaceEquivalentSourceMetric(final SourceGlyphMetric spaceMetric) {
        if (spaceMetric == null) {
            return null;
        }
        return new SourceGlyphMetric(0, 0, 0, 0, spaceMetric.xAdvance());
    }

    static boolean shouldPreserveSourceSpecialGlyph(final int width,
                                                    final int height) {
        return width == 0
                || height == 0
                || (width <= 1 && height <= 1);
    }

    static int[] preservedSpecialGlyphMetrics(final int width,
                                              final int height,
                                              final int xOffset,
                                              final int yOffset,
                                              final int xAdvance) {
        final GlyphRaster glyph = preserveSourceSpecialGlyph(
                0,
                new SourceGlyphMetric(width, height, xOffset, yOffset, xAdvance),
                null,
                null);
        if (glyph == null) {
            return null;
        }
        return new int[]{glyph.width(), glyph.height(), glyph.xOffset(), glyph.yOffset(), glyph.xAdvance()};
    }

    static int[] spaceEquivalentGlyphMetrics(final int codePoint,
                                             final int spaceXAdvance) {
        final SourceGlyphMetric metric = preservedSourceMetric(
                codePoint,
                null,
                new SourceGlyphMetric(0, 0, 0, 0, Math.max(0, spaceXAdvance)));
        if (metric == null) {
            return null;
        }
        return new int[]{metric.width(), metric.height(), metric.xOffset(), metric.yOffset(), metric.xAdvance()};
    }

    static int[] alignedGlyphMetrics(final int rasterWidth,
                                     final int rasterHeight,
                                     final int rasterXOffset,
                                     final int rasterYOffset,
                                     final int rasterXAdvance,
                                     final int sourceWidth,
                                     final int sourceHeight,
                                     final int sourceXOffset,
                                     final int sourceYOffset,
                                     final int sourceXAdvance) {
        final BufferedImage image = rasterWidth > 0 && rasterHeight > 0
                ? new BufferedImage(rasterWidth, rasterHeight, BufferedImage.TYPE_INT_ARGB)
                : null;
        final GlyphRaster aligned = reconcileRasterizedGlyphToSourceMetrics(
                new GlyphRaster(0, "test", "test", image, rasterWidth, rasterHeight, rasterXOffset, rasterYOffset,
                        rasterXAdvance),
                new SourceGlyphMetric(sourceWidth, sourceHeight, sourceXOffset, sourceYOffset, sourceXAdvance));
        return new int[]{
                aligned.width(),
                aligned.height(),
                aligned.xOffset(),
                aligned.yOffset(),
                aligned.xAdvance(),
                aligned.image() == null ? 0 : aligned.image().getWidth(),
                aligned.image() == null ? 0 : aligned.image().getHeight()
        };
    }

    private static GlyphRaster reconcileRasterizedGlyphToSourceMetrics(final GlyphRaster rasterized,
                                                                       final SourceGlyphMetric sourceMetric) {
        if (rasterized == null || sourceMetric == null) {
            return rasterized;
        }
        if (sourceMetric.isSpecialPlaceholder()) {
            return rasterized;
        }

        final ReconciledGlyphBox box = reconcileGlyphBox(rasterized, sourceMetric);
        final BufferedImage alignedImage = alignGlyphImageToSourceMetrics(rasterized, box);
        return new GlyphRaster(
                rasterized.codePoint(),
                rasterized.sourceName(),
                rasterized.faceName(),
                alignedImage,
                box.width(),
                box.height(),
                box.xOffset(),
                box.yOffset(),
                box.xAdvance());
    }

    private static BufferedImage alignGlyphImageToSourceMetrics(final GlyphRaster rasterized,
                                                                final ReconciledGlyphBox box) {
        final int targetWidth = Math.max(0, box.width());
        final int targetHeight = Math.max(0, box.height());
        final BufferedImage sourceImage = rasterized.image();
        if (sourceImage == null || targetWidth <= 0 || targetHeight <= 0) {
            return null;
        }

        final BufferedImage aligned = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        final int destX = rasterized.xOffset() - box.xOffset();
        final int destY = rasterized.yOffset() - box.yOffset();
        final int clippedDestX = Math.max(0, destX);
        final int clippedDestY = Math.max(0, destY);
        final int srcX = Math.max(0, -destX);
        final int srcY = Math.max(0, -destY);
        final int copyWidth = Math.min(sourceImage.getWidth() - srcX, targetWidth - clippedDestX);
        final int copyHeight = Math.min(sourceImage.getHeight() - srcY, targetHeight - clippedDestY);
        if (copyWidth <= 0 || copyHeight <= 0) {
            return aligned;
        }

        final Graphics2D g = aligned.createGraphics();
        try {
            g.drawImage(sourceImage,
                    clippedDestX,
                    clippedDestY,
                    clippedDestX + copyWidth,
                    clippedDestY + copyHeight,
                    srcX,
                    srcY,
                    srcX + copyWidth,
                    srcY + copyHeight,
                    null);
        } finally {
            g.dispose();
        }
        return aligned;
    }

    private static ReconciledGlyphBox reconcileGlyphBox(final GlyphRaster rasterized,
                                                        final SourceGlyphMetric sourceMetric) {
        final int sourceLeft = sourceMetric.xOffset();
        final int sourceTop = sourceMetric.yOffset();
        final int sourceRight = sourceMetric.xOffset() + sourceMetric.width();
        final int sourceBottom = sourceMetric.yOffset() + sourceMetric.height();

        final int rasterLeft = rasterized.xOffset();
        final int rasterTop = rasterized.yOffset();
        final int rasterRight = rasterized.xOffset() + rasterized.width();
        final int rasterBottom = rasterized.yOffset() + rasterized.height();

        final int left = Math.min(sourceLeft, rasterLeft);
        final int top = Math.min(sourceTop, rasterTop);
        final int right = Math.max(sourceRight, rasterRight);
        final int bottom = Math.max(sourceBottom, rasterBottom);
        return new ReconciledGlyphBox(
                Math.max(0, right - left),
                Math.max(0, bottom - top),
                left,
                top,
                Math.max(sourceMetric.xAdvance(), right));
    }

    private static FittedAtlas fitSinglePageAtlas(final OriginalGameFontOverrides.FontOverrideSpec baseSpec,
                                                  final String outputFontPath,
                                                  final SourceBmFont source,
                                                  final RasterizedGlyphs rasterized,
                                                  final float scale) {
        final int sourcePageWidth = preferredPageDimension(baseSpec.pageWidth(), source.scaleWidth());
        final int sourcePageHeight = preferredPageDimension(baseSpec.pageHeight(), source.scaleHeight());
        final int widthStep = runtimePageStep(sourcePageWidth);
        final int heightStep = runtimePageStep(sourcePageHeight);

        OriginalGameFontOverrides.FontOverrideSpec currentSpec = fitStartSpec(
                baseSpec,
                outputFontPath,
                sourcePageWidth,
                sourcePageHeight);
        GlyphLayout currentLayout = layoutGlyphs(currentSpec, rasterized);

        while (currentLayout.pages().size() > 1
                && (currentSpec.pageWidth() < MAX_RUNTIME_PAGE_DIMENSION
                || currentSpec.pageHeight() < MAX_RUNTIME_PAGE_DIMENSION)) {
            final FittedAtlas expanded = chooseExpandedAtlas(baseSpec, outputFontPath, currentSpec, rasterized,
                    widthStep, heightStep);
            if (expanded == null) {
                break;
            }
            currentSpec = expanded.spec();
            currentLayout = expanded.layout();
        }

        return compactSinglePageAtlas(new FittedAtlas(currentSpec, currentLayout));
    }

    static OriginalGameFontOverrides.FontOverrideSpec fitStartSpec(
            final OriginalGameFontOverrides.FontOverrideSpec baseSpec,
            final String outputFontPath,
            final int sourcePageWidth,
            final int sourcePageHeight) {
        return new OriginalGameFontOverrides.FontOverrideSpec(
                outputFontPath,
                baseSpec.primaryFontCandidates(),
                baseSpec.fallbackFontCandidates(),
                preferredPageDimension(baseSpec.pageWidth(), sourcePageWidth),
                preferredPageDimension(baseSpec.pageHeight(), sourcePageHeight));
    }

    private static FittedAtlas compactSinglePageAtlas(final FittedAtlas atlas) {
        if (atlas.layout().pages().size() != 1) {
            return atlas;
        }

        final Page page = atlas.layout().pages().get(0);
        final int compactWidth = compactPageDimension(atlas.spec().pageWidth(), usedPageWidth(page));
        final int compactHeight = compactPageDimension(atlas.spec().pageHeight(), usedPageHeight(page));
        if (compactWidth == atlas.spec().pageWidth() && compactHeight == atlas.spec().pageHeight()) {
            return atlas;
        }

        final OriginalGameFontOverrides.FontOverrideSpec compactSpec = new OriginalGameFontOverrides.FontOverrideSpec(
                atlas.spec().originalFontPath(),
                atlas.spec().primaryFontCandidates(),
                atlas.spec().fallbackFontCandidates(),
                compactWidth,
                compactHeight);
        return new FittedAtlas(compactSpec, resizeLayout(atlas.layout(), compactWidth, compactHeight));
    }

    static int compactPageDimension(final int currentDimension,
                                    final int usedDimension) {
        if (currentDimension <= 0) {
            return Math.max(1, usedDimension);
        }
        if (usedDimension <= 0) {
            return currentDimension;
        }
        return Math.min(currentDimension, Math.max(1, usedDimension));
    }

    private static int usedPageWidth(final Page page) {
        int usedWidth = 0;
        for (GlyphPlacement placement : page.placements()) {
            usedWidth = Math.max(usedWidth, placement.x() + Math.max(0, placement.glyph().width()));
        }
        return usedWidth;
    }

    private static int usedPageHeight(final Page page) {
        int usedHeight = 0;
        for (GlyphPlacement placement : page.placements()) {
            usedHeight = Math.max(usedHeight, placement.y() + Math.max(0, placement.glyph().height()));
        }
        return usedHeight;
    }

    private static GlyphLayout resizeLayout(final GlyphLayout layout,
                                            final int pageWidth,
                                            final int pageHeight) {
        final List<Page> resizedPages = new ArrayList<>(layout.pages().size());
        for (Page page : layout.pages()) {
            resizedPages.add(new Page(pageWidth, pageHeight, new ArrayList<>(page.placements())));
        }
        return new GlyphLayout(resizedPages, layout.backendName(), layout.backendDetails());
    }

    private static FittedAtlas chooseExpandedAtlas(final OriginalGameFontOverrides.FontOverrideSpec baseSpec,
                                                   final String outputFontPath,
                                                   final OriginalGameFontOverrides.FontOverrideSpec currentSpec,
                                                   final RasterizedGlyphs rasterized,
                                                   final int widthStep,
                                                   final int heightStep) {
        final List<FittedAtlas> candidates = new ArrayList<>(3);
        addExpandedAtlasCandidate(candidates, baseSpec, outputFontPath, currentSpec,
                Math.min(MAX_RUNTIME_PAGE_DIMENSION, currentSpec.pageWidth() + widthStep),
                currentSpec.pageHeight(),
                rasterized);
        addExpandedAtlasCandidate(candidates, baseSpec, outputFontPath, currentSpec,
                currentSpec.pageWidth(),
                Math.min(MAX_RUNTIME_PAGE_DIMENSION, currentSpec.pageHeight() + heightStep),
                rasterized);
        addExpandedAtlasCandidate(candidates, baseSpec, outputFontPath, currentSpec,
                Math.min(MAX_RUNTIME_PAGE_DIMENSION, currentSpec.pageWidth() + widthStep),
                Math.min(MAX_RUNTIME_PAGE_DIMENSION, currentSpec.pageHeight() + heightStep),
                rasterized);

        return candidates.stream()
                         .min(Comparator.comparingInt((FittedAtlas atlas) -> atlas.layout().pages().size())
                                        .thenComparingLong(FittedAtlas::area)
                                        .thenComparingInt(atlas -> atlas.spec().pageWidth())
                                        .thenComparingInt(atlas -> atlas.spec().pageHeight()))
                         .orElse(null);
    }

    private static void addExpandedAtlasCandidate(final List<FittedAtlas> candidates,
                                                  final OriginalGameFontOverrides.FontOverrideSpec baseSpec,
                                                  final String outputFontPath,
                                                  final OriginalGameFontOverrides.FontOverrideSpec currentSpec,
                                                  final int pageWidth,
                                                  final int pageHeight,
                                                  final RasterizedGlyphs rasterized) {
        if ((pageWidth == currentSpec.pageWidth() && pageHeight == currentSpec.pageHeight())
                || pageWidth <= 0
                || pageHeight <= 0) {
            return;
        }

        final OriginalGameFontOverrides.FontOverrideSpec expandedSpec = new OriginalGameFontOverrides.FontOverrideSpec(
                outputFontPath,
                baseSpec.primaryFontCandidates(),
                baseSpec.fallbackFontCandidates(),
                pageWidth,
                pageHeight);
        candidates.add(new FittedAtlas(expandedSpec, layoutGlyphs(expandedSpec, rasterized)));
    }

    private static GlyphLayout layoutGlyphs(final OriginalGameFontOverrides.FontOverrideSpec spec,
                                            final RasterizedGlyphs rasterized) {
        final List<Page> pages = new ArrayList<>();
        Page current = new Page(spec.pageWidth(), spec.pageHeight());
        pages.add(current);
        int cursorX = 0;
        int cursorY = 0;
        int rowHeight = 0;

        for (GlyphRaster glyph : rasterized.glyphs()) {
            if (glyph.width() <= 0 || glyph.height() <= 0) {
                current.placements().add(new GlyphPlacement(glyph, 0, 0, pages.size() - 1));
                continue;
            }

            if (cursorX + glyph.width() > spec.pageWidth()) {
                cursorX = 0;
                cursorY += rowHeight + 1;
                rowHeight = 0;
            }
            if (cursorY + glyph.height() > spec.pageHeight()) {
                current = new Page(spec.pageWidth(), spec.pageHeight());
                pages.add(current);
                cursorX = 0;
                cursorY = 0;
                rowHeight = 0;
            }

            current.placements().add(new GlyphPlacement(glyph, cursorX, cursorY, pages.size() - 1));
            cursorX += glyph.width() + 1;
            rowHeight = Math.max(rowHeight, glyph.height());
        }

        return new GlyphLayout(pages, rasterized.backendName(), rasterized.backendDetails());
    }

    private static GlyphRasterizer createRasterizer() {
        final NativeFontRasterizer.RasterizerMode mode = NativeFontRasterizer.requestedMode();
        if (mode != NativeFontRasterizer.RasterizerMode.JAVA2D && NativeFontRasterizer.isAvailable()) {
            return new NativeGlyphRasterizer();
        }
        if (mode == NativeFontRasterizer.RasterizerMode.NATIVE) {
            LOGGER.warn("[SSOptimizer] Native font rasterizer requested but unavailable; using Java2D fallback");
        }
        return new Java2dGlyphRasterizer();
    }

    private static BufferedImage toBufferedImage(final NativeGlyphBitmap glyphBitmap) {
        if (glyphBitmap == null || !glyphBitmap.hasImage()) {
            return null;
        }

        final BufferedImage image = new BufferedImage(glyphBitmap.width(), glyphBitmap.height(),
                BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0,
                glyphBitmap.width(), glyphBitmap.height(),
                glyphBitmap.argbPixels(), 0, glyphBitmap.width());
        return image;
    }

    private static GeneratedFontPack buildPack(final OriginalGameFontOverrides.FontOverrideSpec spec,
                                               final SourceBmFont source,
                                               final FontChain fonts,
                                               final GlyphLayout layout) throws IOException {
        final Map<String, byte[]> resources = new LinkedHashMap<>();
        for (int pageIndex = 0; pageIndex < layout.pages().size(); pageIndex++) {
            final Page page = layout.pages().get(pageIndex);
            final BufferedImage atlas = new BufferedImage(page.width(), page.height(), BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g = atlas.createGraphics();
            try {
                for (GlyphPlacement placement : page.placements()) {
                    if (placement.glyph().image() != null) {
                        g.drawImage(placement.glyph().image(), placement.x(), placement.y(), null);
                    }
                }
            } finally {
                g.dispose();
            }

            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(atlas, "png", out);
            resources.put(spec.pagePath(pageIndex), out.toByteArray());
        }

        final StringBuilder fnt = new StringBuilder(Math.max(8_192, layout.totalGlyphCount() * 96));
        fnt.append("info face=\"")
           .append(escape(layout.primaryFaceName()))
           .append("\" size=")
           .append(source.infoSize())
           .append(" bold=0 italic=0 charset=\"\" unicode=1 stretchH=100 smooth=")
           .append(fonts.antiAlias() ? 1 : 0)
           .append(" aa=")
           .append(fonts.antiAlias() ? 1 : 0)
           .append(fonts.pixelFont() ? " padding=0,0,0,0" : " padding=1,1,1,1")
           .append(" spacing=1,1 outline=0\n");
        fnt.append("common lineHeight=")
           .append(source.lineHeight())
           .append(" base=")
           .append(source.base())
           .append(" scaleW=")
           .append(spec.pageWidth())
           .append(" scaleH=")
           .append(spec.pageHeight())
           .append(" pages=")
           .append(layout.pages().size())
           .append(" packed=0 alphaChnl=2 redChnl=2 greenChnl=2 blueChnl=2\n");

        for (int pageIndex = 0; pageIndex < layout.pages().size(); pageIndex++) {
            fnt.append("page id=")
               .append(pageIndex)
               .append(" file=\"")
               .append(spec.pageFileName(pageIndex))
               .append("\"\n");
        }

        final List<GlyphPlacement> allGlyphs = layout.allPlacementsSorted();
        fnt.append("chars count=")
           .append(allGlyphs.size())
           .append('\n');
        for (GlyphPlacement placement : allGlyphs) {
            final GlyphRaster glyph = placement.glyph();
            final int encodedXAdvance = encodedXAdvanceForRuntimeLayout(glyph.xAdvance(), glyph.xOffset());
            fnt.append("char id=")
               .append(glyph.codePoint())
               .append(" x=")
               .append(placement.x())
               .append(" y=")
               .append(placement.y())
               .append(" width=")
               .append(glyph.width())
               .append(" height=")
               .append(glyph.height())
               .append(" xoffset=")
               .append(glyph.xOffset())
               .append(" yoffset=")
               .append(glyph.yOffset())
               .append(" xadvance=")
               .append(encodedXAdvance)
               .append(" page=")
               .append(placement.page())
               .append(" chnl=15\n");
        }

        resources.put(spec.normalizedOriginalFontPath(),
                fnt.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        LOGGER.info("[SSOptimizer] Generated TTF-backed BMFont override for " + spec.originalFontPath()
                + " with " + layout.pages().size() + " page(s) at " + Instant.now());
        return new GeneratedFontPack(
                resources,
                new GenerationReport(
                        layout.backendName(),
                        layout.backendDetails(),
                        fonts.sourceNames(),
                        fonts.faceNames(),
                        layout.totalGlyphCount(),
                        layout.pages().size(),
                        source.infoSize(),
                        source.lineHeight(),
                        source.base()));
    }

    static int encodedXAdvanceForRuntimeLayout(final int logicalXAdvance,
                                               final int xOffset) {
        return Math.max(0, logicalXAdvance - xOffset);
    }

    static int decodedXAdvanceFromRuntimeLayout(final int encodedXAdvance,
                                                final int xOffset) {
        return Math.max(0, encodedXAdvance + xOffset);
    }

    private static FontRenderPolicy renderPolicyForSpec(final OriginalGameFontOverrides.FontOverrideSpec spec,
                                                        final SourceBmFont source) {
        return new FontRenderPolicy(
                source.antiAlias(),
                true,
                false);
    }

    private static void applyTextHints(final Graphics2D g,
                                       final boolean antiAlias,
                                       final boolean fractionalMetrics) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                antiAlias ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                antiAlias ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                fractionalMetrics ? RenderingHints.VALUE_FRACTIONALMETRICS_ON
                        : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g.setBackground(new Color(0, 0, 0, 0));
    }

    private static String escape(final String text) {
        return text.replace('"', '_').replaceAll("\\s+", "_");
    }

    private interface GlyphRasterizer extends AutoCloseable {
        String backendName();

        String backendDetails(FontChain chain);

        GlyphRaster rasterizeGlyph(int codePoint,
                                   int baseline,
                                   FontChain chain);

        @Override
        default void close() {
        }
    }

    @FunctionalInterface
    private interface FontPackFactory {
        GeneratedFontPack generate() throws IOException, FontFormatException;
    }

    record GeneratedFontPack(Map<String, byte[]> resources,
                             GenerationReport report) {
        GeneratedFontPack {
            resources = Map.copyOf(resources);
        }
    }

    record GenerationReport(String backendName,
                            String backendDetails,
                            List<String> selectedFontSources,
                            List<String> selectedFontFaces,
                            int glyphCount,
                            int pageCount,
                            int infoSize,
                            int lineHeight,
                            int base) {
        GenerationReport {
            backendDetails = backendDetails == null ? "" : backendDetails;
            selectedFontSources = List.copyOf(selectedFontSources);
            selectedFontFaces = List.copyOf(selectedFontFaces);
        }
    }

    private record FontChain(List<LoadedFont> fonts,
                             boolean antiAlias,
                             boolean fractionalMetrics,
                             boolean pixelFont,
                             boolean substituteLowercaseWithUppercase) {
        private FontChain {
            fonts = List.copyOf(fonts);
        }

        LoadedFont selectFont(final int codePoint) {
            for (LoadedFont loadedFont : fonts) {
                if (loadedFont.font().canDisplay(codePoint)) {
                    return loadedFont;
                }
            }
            return fonts.getFirst();
        }

        List<String> sourceNames() {
            return fonts.stream().map(LoadedFont::sourceName).toList();
        }

        List<String> faceNames() {
            return fonts.stream().map(LoadedFont::faceName).distinct().toList();
        }
    }

    private record FontRenderPolicy(boolean antiAlias,
                                    boolean fractionalMetrics,
                                    boolean pixelFont) {
    }

    private record ReconciledGlyphBox(int width,
                                      int height,
                                      int xOffset,
                                      int yOffset,
                                      int xAdvance) {
    }

    private record LoadedFont(String sourceName,
                              Path sourcePath,
                              Font font) {
        String faceName() {
            return font.getFontName(Locale.ROOT);
        }
    }

    private record SourceBmFont(int infoSize,
                                boolean antiAlias,
                                int lineHeight,
                                int base,
                                int scaleWidth,
                                int scaleHeight,
                                List<Integer> codePoints,
                                Map<Integer, SourceGlyphMetric> glyphMetrics) {
        private static SourceBmFont parse(final Path path) throws IOException {
            int infoSize = 16;
            boolean antiAlias = true;
            int lineHeight = 16;
            int base = 12;
            int scaleWidth = 256;
            int scaleHeight = 256;
            final List<Integer> codePoints = new ArrayList<>();
            final Map<Integer, SourceGlyphMetric> glyphMetrics = new LinkedHashMap<>();

            for (String rawLine : Files.readAllLines(path, StandardCharsets.ISO_8859_1)) {
                final String line = rawLine.trim();
                if (line.startsWith("info ")) {
                    infoSize = parseIntProperty(line, "size", infoSize);
                    antiAlias = parseIntProperty(line, "aa", antiAlias ? 1 : 0) > 0
                            || parseIntProperty(line, "smooth", antiAlias ? 1 : 0) > 0;
                } else if (line.startsWith("common ")) {
                    lineHeight = parseIntProperty(line, "lineHeight", lineHeight);
                    base = parseIntProperty(line, "base", base);
                    scaleWidth = parseIntProperty(line, "scaleW", scaleWidth);
                    scaleHeight = parseIntProperty(line, "scaleH", scaleHeight);
                } else if (line.startsWith("char ")) {
                    final int codePoint = parseIntProperty(line, "id", 0);
                    final int xOffset = parseIntProperty(line, "xoffset", 0);
                    codePoints.add(codePoint);
                    glyphMetrics.put(codePoint, new SourceGlyphMetric(
                            parseIntProperty(line, "width", 0),
                            parseIntProperty(line, "height", 0),
                            xOffset,
                            parseIntProperty(line, "yoffset", 0),
                            decodedXAdvanceFromRuntimeLayout(parseIntProperty(line, "xadvance", 0), xOffset)));
                }
            }

            if (codePoints.isEmpty()) {
                throw new IOException("No glyphs found in BMFont file: " + path);
            }
            return new SourceBmFont(infoSize, antiAlias, lineHeight, base, scaleWidth, scaleHeight,
                    List.copyOf(codePoints), Map.copyOf(glyphMetrics));
        }

        private static int scaleSignedMetric(final int value,
                                             final float scale) {
            final int sign = value < 0 ? -1 : 1;
            return sign * scalePositiveMetric(Math.abs(value), scale);
        }

        private static int scalePositiveMetric(final int value,
                                               final float scale) {
            if (!Float.isFinite(scale) || scale <= 0.0f) {
                return Math.max(1, value);
            }
            return Math.max(1, Math.round(Math.max(1, value) * scale));
        }

        private static int parseIntProperty(final String line,
                                            final String key,
                                            final int defaultValue) {
            final String token = key + '=';
            final int start = line.indexOf(token);
            if (start < 0) {
                return defaultValue;
            }
            int end = line.indexOf(' ', start + token.length());
            if (end < 0) {
                end = line.length();
            }
            try {
                return Integer.parseInt(line.substring(start + token.length(), end).replace("\"", ""));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }

        SourceBmFont scaled(final float scale) {
            final Map<Integer, SourceGlyphMetric> scaledGlyphMetrics = new LinkedHashMap<>(glyphMetrics.size());
            for (Map.Entry<Integer, SourceGlyphMetric> entry : glyphMetrics.entrySet()) {
                scaledGlyphMetrics.put(entry.getKey(), entry.getValue().scaled(scale));
            }
            return new SourceBmFont(
                    scaleSignedMetric(infoSize, scale),
                    antiAlias,
                    scalePositiveMetric(lineHeight, scale),
                    scalePositiveMetric(base, scale),
                    scalePositiveMetric(scaleWidth, scale),
                    scalePositiveMetric(scaleHeight, scale),
                    codePoints,
                    Map.copyOf(scaledGlyphMetrics));
        }

        float averageAdvance(final String sample) {
            int count = 0;
            int totalAdvance = 0;
            for (int index = 0; index < sample.length(); ) {
                final int codePoint = sample.codePointAt(index);
                final SourceGlyphMetric metric = glyphMetrics.get(codePoint);
                if (metric != null && metric.xAdvance() > 0) {
                    totalAdvance += metric.xAdvance();
                    count++;
                }
                index += Character.charCount(codePoint);
            }
            return count == 0 ? 0f : (float) totalAdvance / count;
        }

        float averageHeight(final String sample) {
            int count = 0;
            int totalHeight = 0;
            for (int index = 0; index < sample.length(); ) {
                final int codePoint = sample.codePointAt(index);
                final SourceGlyphMetric metric = glyphMetrics.get(codePoint);
                if (metric != null && metric.height() > 0) {
                    totalHeight += metric.height();
                    count++;
                }
                index += Character.charCount(codePoint);
            }
            return count == 0 ? 0f : (float) totalHeight / count;
        }
    }

    private record SourceGlyphMetric(int width,
                                     int height,
                                     int xOffset,
                                     int yOffset,
                                     int xAdvance) {
        private static int scaleMetric(final int value,
                                       final float scale) {
            if (!Float.isFinite(scale) || scale <= 0f) {
                return Math.max(0, value);
            }
            return Math.max(0, Math.round(Math.max(0, value) * scale));
        }

        private static int scaleSignedMetric(final int value,
                                             final float scale) {
            final int sign = value < 0 ? -1 : 1;
            return sign * scaleMetric(Math.abs(value), scale);
        }

        boolean isSpecialPlaceholder() {
            return shouldPreserveSourceSpecialGlyph(width, height);
        }

        SourceGlyphMetric scaled(final float scale) {
            return new SourceGlyphMetric(
                    scaleMetric(width, scale),
                    scaleMetric(height, scale),
                    scaleSignedMetric(xOffset, scale),
                    scaleSignedMetric(yOffset, scale),
                    scaleMetric(xAdvance, scale));
        }
    }

    private static final class Java2dGlyphRasterizer implements GlyphRasterizer {
        @Override
        public String backendName() {
            return "java2d";
        }

        @Override
        public String backendDetails(final FontChain chain) {
            return "textAA=" + (chain.antiAlias() ? "on" : "off")
                    + ", fractionalMetrics=" + (chain.fractionalMetrics() ? "on" : "off")
                    + ", renderQuality=quality";
        }

        @Override
        public GlyphRaster rasterizeGlyph(final int codePoint,
                                          final int baseline,
                                          final FontChain chain) {
            final LoadedFont loadedFont = chain.selectFont(codePoint);
            final Font font = loadedFont.font();
            final String text = new String(Character.toChars(codePoint));

            final BufferedImage scratch = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g = scratch.createGraphics();
            try {
                applyTextHints(g, chain.antiAlias(), chain.fractionalMetrics());
                final FontRenderContext frc = g.getFontRenderContext();
                final GlyphVector gv = font.createGlyphVector(frc, text);
                final java.awt.Rectangle pixelBounds = gv.getPixelBounds(frc, 0f, 0f);
                final GlyphMetrics gm = gv.getGlyphMetrics(0);
                final int width = Math.max(0, pixelBounds.width);
                final int height = Math.max(0, pixelBounds.height);
                final int xOffset = pixelBounds.x;
                final int yOffset = baseline + pixelBounds.y;
                final int xAdvance = Math.max(1, Math.round(gm.getAdvance()));

                BufferedImage image = null;
                if (width > 0 && height > 0) {
                    image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    final Graphics2D gg = image.createGraphics();
                    try {
                        applyTextHints(gg, chain.antiAlias(), chain.fractionalMetrics());
                        gg.setColor(Color.WHITE);
                        gg.setFont(font);
                        gg.drawGlyphVector(gv, -pixelBounds.x, -pixelBounds.y);
                    } finally {
                        gg.dispose();
                    }
                }

                return new GlyphRaster(codePoint, loadedFont.sourceName(), loadedFont.faceName(), image, width, height,
                        xOffset, yOffset, xAdvance);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class NativeGlyphRasterizer implements GlyphRasterizer {
        private final Java2dGlyphRasterizer fallback    = new Java2dGlyphRasterizer();
        private final Map<String, Long>     faceHandles = new LinkedHashMap<>();

        @Override
        public String backendName() {
            return "native-freetype";
        }

        @Override
        public String backendDetails(final FontChain chain) {
            return NativeFontRasterizer.describeSettings(chain.antiAlias());
        }

        @Override
        public GlyphRaster rasterizeGlyph(final int codePoint,
                                          final int baseline,
                                          final FontChain chain) {
            final LoadedFont loadedFont = chain.selectFont(codePoint);
            final long faceHandle = resolveFaceHandle(loadedFont, chain.antiAlias());
            if (faceHandle == 0L) {
                return fallback.rasterizeGlyph(codePoint, baseline, chain);
            }

            final NativeGlyphBitmap bitmap = NativeFontRasterizer.rasterizeGlyph(faceHandle, codePoint, baseline);
            if (bitmap == null) {
                return fallback.rasterizeGlyph(codePoint, baseline, chain);
            }

            return new GlyphRaster(
                    codePoint,
                    loadedFont.sourceName(),
                    loadedFont.faceName(),
                    toBufferedImage(bitmap),
                    bitmap.width(),
                    bitmap.height(),
                    bitmap.xOffset(),
                    bitmap.yOffset(),
                    bitmap.xAdvance());
        }

        @Override
        public void close() {
            for (Long faceHandle : faceHandles.values()) {
                NativeFontRasterizer.destroyFace(faceHandle == null ? 0L : faceHandle);
            }
            faceHandles.clear();
        }

        private long resolveFaceHandle(final LoadedFont loadedFont,
                                       final boolean antiAlias) {
            final Path sourcePath = loadedFont.sourcePath();
            if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
                return 0L;
            }

            final String key = sourcePath.toAbsolutePath() + "#" + Float.floatToIntBits(loadedFont.font().getSize2D())
                    + '#' + antiAlias + '#' + NativeFontRasterizer.describeSettings(antiAlias);
            final Long cached = faceHandles.get(key);
            if (cached != null) {
                return cached;
            }

            final long handle = NativeFontRasterizer.createFace(sourcePath, loadedFont.font().getSize2D(), antiAlias);
            if (handle != 0L) {
                faceHandles.put(key, handle);
            }
            return handle;
        }
    }

    private record GlyphRaster(int codePoint,
                               String sourceName,
                               String faceName,
                               BufferedImage image,
                               int width,
                               int height,
                               int xOffset,
                               int yOffset,
                               int xAdvance) {
        int sortHeight() {
            return Math.max(height, 1);
        }
    }

    private record GlyphPlacement(GlyphRaster glyph,
                                  int x,
                                  int y,
                                  int page) {
    }

    private record Page(int width,
                        int height,
                        List<GlyphPlacement> placements) {
        private Page(final int width,
                     final int height) {
            this(width, height, new ArrayList<>());
        }
    }

    private record GlyphLayout(List<Page> pages,
                               String backendName,
                               String backendDetails) {
        private GlyphLayout {
            pages = List.copyOf(pages);
            backendDetails = backendDetails == null ? "" : backendDetails;
        }

        int totalGlyphCount() {
            return pages.stream().mapToInt(page -> page.placements().size()).sum();
        }

        List<GlyphPlacement> allPlacementsSorted() {
            final List<GlyphPlacement> placements = new ArrayList<>();
            for (Page page : pages) {
                placements.addAll(page.placements());
            }
            placements.sort(Comparator.comparingInt((GlyphPlacement value) -> value.glyph().codePoint()));
            return placements;
        }

        String primaryFaceName() {
            for (Page page : pages) {
                for (GlyphPlacement placement : page.placements()) {
                    if (placement.glyph().faceName() != null && !placement.glyph().faceName().isBlank()) {
                        return placement.glyph().faceName();
                    }
                }
            }
            return "SSOptimizerTTF";
        }
    }

    private record RasterizedGlyphs(List<GlyphRaster> glyphs,
                                    String backendName,
                                    String backendDetails) {
        private RasterizedGlyphs {
            glyphs = List.copyOf(glyphs);
            backendDetails = backendDetails == null ? "" : backendDetails;
        }
    }

    private record FittedAtlas(OriginalGameFontOverrides.FontOverrideSpec spec,
                               GlyphLayout layout) {
        long area() {
            return (long) spec.pageWidth() * (long) spec.pageHeight();
        }
    }
}