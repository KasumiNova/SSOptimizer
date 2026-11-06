package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.loading.TextureCompressionSupport.Format;

import java.util.Locale;
import java.util.function.Function;

public final class TextureCompressionEligibility {
    static final int  MIN_MAX_DIMENSION   = 64;
    static final long MIN_RGBA_BYTES      = 16L * 1024;
    static final int  BC1_MIN_MAX_DIMENSION = 256;
    static final String EXCLUDED_PATHS_PROPERTY = "ssoptimizer.texcompress.excludePaths";
    static final String DEFAULT_EXCLUDED_PATHS = "background,starscape,nebula,illustration,/fx/";

    private static final String[] EXCLUDED_NAME_SUFFIXES = {"_normal", "_surface", "_material"};

    private TextureCompressionEligibility() {
    }

    public static Format selectFormat(final String resourcePath,
                                      final int width,
                                      final int height,
                                      final AlphaKind alphaKind,
                                      final Format glPreferredFormat) {
        return selectFormat(resourcePath, width, height, alphaKind,
                glPreferredFormat, TextureCompressionSupport.isS3tcAvailable(),
                TextureCompressionSupport.isBc1ForOpaqueEnabled());
    }

    static Format selectFormat(final String resourcePath,
                               final int width,
                               final int height,
                               final AlphaKind alphaKind,
                               final Format glPreferredFormat,
                               final boolean s3tcAvailable,
                               final boolean bc1ForOpaque) {
        if (glPreferredFormat == null || glPreferredFormat == Format.NONE) {
            return Format.NONE;
        }
        final int maxDimension = Math.max(width, height);
        if (maxDimension < MIN_MAX_DIMENSION) {
            return Format.NONE;
        }
        if ((long) width * height * 4L < MIN_RGBA_BYTES) {
            return Format.NONE;
        }
        final String path = resourcePath == null ? "" : resourcePath;
        if (hasExcludedNameSuffix(path)) {
            return Format.NONE;
        }
        if (isPathExcludedByProperty(path)) {
            return Format.NONE;
        }
        if (LazyTextureManager.isFontAtlasWithoutMipmaps(path)) {
            return Format.NONE;
        }

        final AlphaKind kind = alphaKind == null ? AlphaKind.FULL : alphaKind;
        final boolean bc1Eligible = maxDimension >= BC1_MIN_MAX_DIMENSION && s3tcAvailable;
        if (glPreferredFormat == Format.BC3) {
            if (bc1Eligible && kind != AlphaKind.FULL) {
                return Format.BC1;
            }
            return Format.BC3;
        }
        if (bc1ForOpaque && bc1Eligible && kind == AlphaKind.OPAQUE) {
            return Format.BC1;
        }
        return glPreferredFormat;
    }

    static boolean usesPunchThroughAlpha(final Format format, final AlphaKind alphaKind) {
        return format == Format.BC1 && alphaKind == AlphaKind.BINARY;
    }

    static SsobcContainer resolveCompressedUpload(final String resourcePath,
                                                  final int textureWidth,
                                                  final int textureHeight,
                                                  final AlphaKind alphaKind,
                                                  final Format glPreferredFormat,
                                                  final boolean s3tcAvailable,
                                                  final String sourceHash,
                                                  final boolean mipmaps,
                                                  final Function<CompressedTextureCache.Key, SsobcContainer> cacheLookup) {
        final Format format = selectFormat(
                resourcePath, textureWidth, textureHeight, alphaKind, glPreferredFormat, s3tcAvailable,
                TextureCompressionSupport.isBc1ForOpaqueEnabled());
        if (format == Format.NONE || sourceHash == null || sourceHash.isBlank()) {
            return null;
        }
        return cacheLookup.apply(
                new CompressedTextureCache.Key(sourceHash, textureWidth, textureHeight, mipmaps, format,
                        TextureCompressionScheduler.resolveQuality(resourcePath)));
    }

    static boolean isPathExcludedByProperty(final String resourcePath) {
        return TextureCompressionScheduler.pathMatchesAny(resourcePath,
                TextureCompressionScheduler.splitPatterns(
                        System.getProperty(EXCLUDED_PATHS_PROPERTY, DEFAULT_EXCLUDED_PATHS)));
    }

    private static boolean hasExcludedNameSuffix(final String resourcePath) {
        final String normalized = resourcePath.replace('\\', '/');
        final int slash = normalized.lastIndexOf('/');
        final String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        final int dot = fileName.lastIndexOf('.');
        final String baseName = (dot > 0 ? fileName.substring(0, dot) : fileName).toLowerCase(Locale.ROOT);
        for (final String suffix : EXCLUDED_NAME_SUFFIXES) {
            if (baseName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
