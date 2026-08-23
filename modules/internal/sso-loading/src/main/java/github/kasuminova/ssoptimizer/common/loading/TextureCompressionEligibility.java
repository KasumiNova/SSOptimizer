package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.loading.TextureCompressionSupport.Format;

import java.util.Locale;
import java.util.function.Function;

/**
 * 纹理压缩适用性判定（纯逻辑，设计：docs/design/gpu-texture-compression.md §3.4）。
 * <p>
 * 排除面：
 * <ul>
 * <li>尺寸下限：max(w,h) &lt; 64 或原始 RGBA 字节 &lt; 16KB（小精灵质量洼地，
 * 单张收益绝对值小）；</li>
 * <li>材质/法线贴图：文件名（忽略扩展名、忽略大小写）以
 * {@code _normal}/{@code _surface}/{@code _material} 结尾；</li>
 * <li>{@code ssoptimizer.texcompress.excludePaths} 命中的路径（用户配置的完全排除面，
 * 保持 RGBA8 上传）；</li>
 * <li>字体图集路径（复用 {@link LazyTextureManager#isFontAtlasWithoutMipmaps} 的判定）。</li>
 * </ul>
 * 格式选择（质量优先，按实际像素 alpha 内容 {@link AlphaKind} 而非 ColorModel 声明）：
 * <ul>
 * <li>bptc 可用（首选 BC7）：一律 BC7——大而平滑的不透明贴图（星云背景等）走 BC1
 * 会产生 4bpp 块效应色带，不可接受。唯一例外：用户显式开启
 * {@code ssoptimizer.texcompress.bc1ForOpaque=true} 且 S3TC 可用时，实际全不透明的
 * 大图（max(w,h) ≥ 256）用 BC1（省显存换画质，默认关闭）；</li>
 * <li>S3TC 回退路径（首选 BC3，即 bptc 不可用或用户强制 bc3）：实际全不透明 → BC1；
 * alpha 仅 0/255 → BC1（1-bit punch-through alpha）；其余 → BC3。
 * BC1 仍有尺寸下限（max(w,h) ≥ 256，小尺寸 BC1 质量不达标）。</li>
 * </ul>
 * 首选为 NONE 或命中排除面 → 不压缩。
 */
public final class TextureCompressionEligibility {
    /** 尺寸下限：max(w,h) 低于此不压缩。 */
    static final int  MIN_MAX_DIMENSION   = 64;
    /** 原始 RGBA 字节下限：低于此不压缩。 */
    static final long MIN_RGBA_BYTES      = 16L * 1024;
    /** BC1 启用下限：max(w,h) 达到此值才允许 BC1（小尺寸 BC1 质量不达标）。 */
    static final int  BC1_MIN_MAX_DIMENSION = 256;
    /**
     * 压缩排除路径模式（逗号分隔子串，大小写不敏感，匹配规范化后的资源路径）：
     * 命中的贴图完全不压缩（保持 RGBA8 上传）。
     * 默认 {@value #DEFAULT_EXCLUDED_PATHS}：背景/插画/星云/特效类大面积平滑渐变贴图
     * 在 high 档 BC7 下仍有可见色阶（实测），画质优先直接排除；
     * 置空字符串可恢复全量压缩。
     */
    static final String EXCLUDED_PATHS_PROPERTY = "ssoptimizer.texcompress.excludePaths";
    /** {@link #EXCLUDED_PATHS_PROPERTY} 的默认排除面。 */
    static final String DEFAULT_EXCLUDED_PATHS = "background,starscape,nebula,illustration,/fx/";

    private static final String[] EXCLUDED_NAME_SUFFIXES = {"_normal", "_surface", "_material"};

    private TextureCompressionEligibility() {
    }

    /**
     * 判定入口（S3TC 可用性与 bc1ForOpaque 开关自查）。
     *
     * @param glPreferredFormat GL 首选格式（{@link TextureCompressionSupport#preferredFormat()}）
     * @return 应使用的压缩格式；不压缩返回 {@link Format#NONE}
     */
    public static Format selectFormat(final String resourcePath,
                                      final int width,
                                      final int height,
                                      final AlphaKind alphaKind,
                                      final Format glPreferredFormat) {
        return selectFormat(resourcePath, width, height, alphaKind,
                glPreferredFormat, TextureCompressionSupport.isS3tcAvailable(),
                TextureCompressionSupport.isBc1ForOpaqueEnabled());
    }

    /**
     * 判定纯函数（全部输入显式传入，单测直调）。
     *
     * @param width/height 实际压缩输入的纹理尺寸（conversion result 的 textureWidth/Height）
     * @param alphaKind 实际像素 alpha 内容（null 按 {@link AlphaKind#FULL} 保守处理）
     */
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
            // S3TC 回退路径（bptc 不可用或用户强制 bc3）：按实际 alpha 内容选择
            if (bc1Eligible && kind != AlphaKind.FULL) {
                // OPAQUE → BC1 无 alpha；BINARY → BC1 带 1-bit punch-through alpha
                return Format.BC1;
            }
            return Format.BC3;
        }
        // 首选 BC7（bptc 可用）：质量优先一律 BC7；bc1ForOpaque 显式开启时
        // 实际全不透明的大图用 BC1（4bpp 省显存，代价是平滑渐变可能出现色带）
        if (bc1ForOpaque && bc1Eligible && kind == AlphaKind.OPAQUE) {
            return Format.BC1;
        }
        return glPreferredFormat;
    }

    /**
     * 所选 BC1 是否需要 1-bit punch-through alpha（仅 BINARY 内容走到 BC1 时为 true），
     * 供压缩调用侧决定 native 的 useAlpha 参数。
     */
    static boolean usesPunchThroughAlpha(final Format format, final AlphaKind alphaKind) {
        return format == Format.BC1 && alphaKind == AlphaKind.BINARY;
    }

    /**
     * 上传分流决策（纯函数，单测直调）：适用压缩且压缩缓存命中 → 返回 SSOBC 容器；
     * 否则返回 null（调用方走未压缩路径并按需投递后台压缩）。
     *
     * @param cacheLookup 缓存查询函数（生产环境为 {@link CompressedTextureCache#load}）
     * @return 命中的压缩容器；不压缩或未命中返回 null
     */
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

    /** 路径是否命中 {@link #EXCLUDED_PATHS_PROPERTY} 排除模式（判定现场读属性，测试可注入）。 */
    static boolean isPathExcludedByProperty(final String resourcePath) {
        return TextureCompressionScheduler.pathMatchesAny(resourcePath,
                TextureCompressionScheduler.splitPatterns(
                        System.getProperty(EXCLUDED_PATHS_PROPERTY, DEFAULT_EXCLUDED_PATHS)));
    }

    /** 文件名（忽略扩展名与大小写）以材质/法线后缀结尾。 */
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
