package github.kasuminova.ssoptimizer.common.font;

import org.apache.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Lazily generates TTF-backed BMFont-compatible resources for selected original
 * game font paths. Unmatched paths return {@code null}, so mod-provided fonts keep
 * using the legacy bitmap pipeline untouched.
 */
public final class OriginalGameFontOverrides {
    static final String MOD_ID               = "ssoptimizer";
    static final String MOD_FONT_DIR_NAME    = "fonts";
    public static final String ENABLE_PROPERTY     = "ssoptimizer.font.ttf.enable";
    public static final String FONT_DIR_PROPERTY   = "ssoptimizer.font.ttf.dir";
    public static final String DEBUG_LOG_PROPERTY  = "ssoptimizer.font.ttf.debug";
    public static final String EXPORT_PROPERTY     = FontArtifactExporter.EXPORT_PROPERTY;
    public static final String EXPORT_DIR_PROPERTY = FontArtifactExporter.EXPORT_DIR_PROPERTY;

    /**
     * 唯一内建 profile：字重统一策略为「CJK 全系 MiSans-Regular（Medium 在 CJK 正文
     * 观感过重）；orbitron bold 角色映射到 semibold 字宽（bold 过粗发白）」。
     * 声明位置必须在 {@link #OVERRIDES} 之前（clinit 顺序依赖）。
     */
    private static final FontProfile DEFAULT_PROFILE = new FontProfile(
            "original-match",
            List.of("lte50549.ttf", "MiSans-Regular.ttf"),
            List.of("lte50549.ttf", "MiSans-Regular.ttf"),
            List.of("orbitron-light.ttf", "MiSans-Regular.ttf"),
            List.of("orbitron-semibold.ttf", "MiSans-Regular.ttf"),
            List.of("Oxanium-Medium.ttf", "MiSans-Regular.ttf"),
            List.of("MiSans-Regular.ttf"),
            List.of("MiSans-Regular.ttf", "font.ttf"),
            List.of("MiSans-Regular.ttf", "font.ttf")
    );

    private static final Logger                        LOGGER           = Logger.getLogger(OriginalGameFontOverrides.class);
    private static final Map<String, FontOverrideSpec> OVERRIDES        = createOverrideSpecs();
    private static final Object                        LOCK             = new Object();

    private static volatile Map<String, byte[]> generatedResources      = Collections.emptyMap();
    private static volatile boolean              initializationAttempted = false;

    private OriginalGameFontOverrides() {
    }

    public static InputStream openStream(final String resourcePath) {
        if (!isEnabled()) {
            return null;
        }

        final String normalized = normalize(resourcePath);
        if (!isOverriddenPath(normalized)) {
            return null;
        }

        ensureInitialized();
        final byte[] data = generatedResources.get(normalized);
        if (data == null) {
            if (Boolean.getBoolean(DEBUG_LOG_PROPERTY)) {
                LOGGER.info("[SSOptimizer][FontDebug] openStream MISS (not in generatedResources): " + normalized);
            }
            return null;
        }
        if (Boolean.getBoolean(DEBUG_LOG_PROPERTY)) {
            LOGGER.info("[SSOptimizer][FontDebug] openStream HIT (" + data.length + " bytes): " + normalized);
        }
        return new ByteArrayInputStream(data);
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "true"));
    }

    public static boolean isOverriddenPath(final String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return false;
        }
        if (OVERRIDES.containsKey(resourcePath)) {
            return true;
        }
        for (FontOverrideSpec spec : OVERRIDES.values()) {
            if (spec.ownsResource(resourcePath)) {
                return true;
            }
        }
        return false;
    }

    public static String normalize(final String resourcePath) {
        if (resourcePath == null) {
            return "";
        }
        return resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
    }

    private static void ensureInitialized() {
        if (initializationAttempted) {
            return;
        }

        synchronized (LOCK) {
            if (initializationAttempted) {
                return;
            }
            initializationAttempted = true;

            final Map<String, byte[]> generated = new HashMap<>();
            final Path fontDir = resolveFontDir();
            LOGGER.info("[SSOptimizer] Original font override profile=" + DEFAULT_PROFILE.name()
                    + " fontDir=" + fontDir);
            for (FontOverrideSpec spec : OVERRIDES.values()) {
                try {
                    final TtfBmFontGenerator.GeneratedFontPack pack = TtfBmFontGenerator.generate(spec, fontDir);
                    generated.putAll(pack.resources());
                    LOGGER.info("[SSOptimizer] Font override ready for " + spec.originalFontPath()
                            + " backend=" + pack.report().backendName()
                            + (pack.report().backendDetails().isBlank() ? "" : " details=" + pack.report().backendDetails())
                            + " fonts=" + String.join(", ", pack.report().selectedFontSources())
                            + " infoSize=" + pack.report().infoSize()
                            + " lineHeight=" + pack.report().lineHeight());
                    if (FontArtifactExporter.isEnabled()) {
                        try {
                            final Path exportRoot = FontArtifactExporter.exportConfigured(spec, pack);
                            if (exportRoot != null) {
                                LOGGER.info("[SSOptimizer] Exported generated font artifacts for " + spec.originalFontPath()
                                        + " to " + exportRoot);
                            }
                        } catch (IOException exportFailure) {
                            LOGGER.warn("[SSOptimizer] Failed to export generated font artifacts for " + spec.originalFontPath(), exportFailure);
                        }
                    }
                    if (Boolean.getBoolean(DEBUG_LOG_PROPERTY)) {
                        LOGGER.info("[SSOptimizer] Generated TTF-backed original font override for " + spec.originalFontPath());
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[SSOptimizer] Failed to generate original font override for " + spec.originalFontPath() + ", falling back to bundled bitmap font", t);
                }
            }
            generatedResources = generated;

            if (!generated.isEmpty()) {
                LOGGER.info("[SSOptimizer] Generated " + generated.size() + " TTF-backed original font resource(s)");
            }
        }
    }

    private static Path resolveFontDir() {
        final String configured = System.getProperty(FONT_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return resolveDefaultFontDir(System.getProperty("com.fs.starfarer.settings.paths.mods", "./mods"),
                    Path.of("").toAbsolutePath().normalize());
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    static Path resolveDefaultFontDir(final String modsPath,
                                      final Path workingDir) {
        final String normalizedModsPath = modsPath == null || modsPath.isBlank() ? "./mods" : modsPath;
        return workingDir.resolve(normalizedModsPath)
                .normalize()
                .resolve(MOD_ID)
                .resolve(MOD_FONT_DIR_NAME)
                .normalize();
    }

    static Path currentFontDir() {
        return resolveFontDir();
    }

    private static Map<String, FontOverrideSpec> createOverrideSpecs() {
        final Map<String, FontOverrideSpec> specs = new LinkedHashMap<>();
        final FontProfile profile = DEFAULT_PROFILE;

        registerFamily(specs, profile, FontRole.INSIGNIA_REGULAR,
                "graphics/fonts/insignia12.fnt",
                "graphics/fonts/insignia12a.fnt",
                "graphics/fonts/insignia15LTaa.fnt",
                "graphics/fonts/insignia16.fnt",
                "graphics/fonts/insignia16a.fnt",
                "graphics/fonts/insignia17LTaa.fnt",
                "graphics/fonts/insignia17LTAaa.fnt",
                "graphics/fonts/insignia21LTaa.fnt",
                "graphics/fonts/insignia25LTaa.fnt",
                "graphics/fonts/insignia42LTaa.fnt"
        );
        registerFamily(specs, profile, FontRole.INSIGNIA_BOLD,
                "graphics/fonts/insignia12bold.fnt"
        );

        registerFamily(specs, profile, FontRole.ORBITRON_BOLD, // Bold 观感更好
                "graphics/fonts/orbitron10.fnt",
                "graphics/fonts/orbitron12condensed.fnt",
                "graphics/fonts/orbitron12.fnt",
                "graphics/fonts/orbitron16.fnt",
                "graphics/fonts/orbitron20.fnt",
                "graphics/fonts/orbitron20aa.fnt",
                "graphics/fonts/orbitron24aa.fnt"
        );
        registerFamily(specs, profile, FontRole.ORBITRON_BOLD,
                "graphics/fonts/orbitron12bold.fnt",
                "graphics/fonts/orbitron20bold.fnt",
                "graphics/fonts/orbitron20aabold.fnt",
                "graphics/fonts/orbitron24aabold.fnt"
        );

        // victor 族全量覆盖：MapEntityIcon 按缩放阈值在 victor14/victor16 间切换，
        // 缺任何一个都会让星图实体标签回退到无 CJK 字形的原版位图管线
        for (String victorPath : List.of(
                "graphics/fonts/victor10.fnt",
                "graphics/fonts/victor12.fnt",
                "graphics/fonts/victor14.fnt",
                "graphics/fonts/victor16.fnt",
                "graphics/fonts/victor21.fnt"
        )) {
            register(specs, new FontOverrideSpec(
                    victorPath,
                    profile.victorPrimary(),
                    profile.victorFallback(),
                    2048,
                    2048
            ));
        }

        return Collections.unmodifiableMap(specs);
    }

    static FontProfile activeProfile() {
        return DEFAULT_PROFILE;
    }

    private static void registerFamily(final Map<String, FontOverrideSpec> specs,
                                       final FontProfile profile,
                                       final FontRole role,
                                       final String... originalFontPaths) {
        final List<String> primary = primaryCandidates(profile, role);
        // bold 角色走独立回退链（boldFallback），当前与 fallback 完全一致（全系 Regular）
        final List<String> fallback = role == FontRole.INSIGNIA_BOLD || role == FontRole.ORBITRON_BOLD
                ? profile.boldFallback()
                : profile.fallback();
        for (String originalFontPath : originalFontPaths) {
            register(specs, new FontOverrideSpec(
                    originalFontPath,
                    primary,
                    fallback,
                    2048,
                    2048
            ));
        }
    }

    private static List<String> primaryCandidates(final FontProfile profile,
                                                  final FontRole role) {
        return switch (role) {
            case INSIGNIA_REGULAR -> profile.insigniaPrimary();
            case INSIGNIA_BOLD -> profile.insigniaBoldPrimary();
            case ORBITRON_REGULAR -> profile.orbitronRegularPrimary();
            case ORBITRON_BOLD -> profile.orbitronBoldPrimary();
        };
    }

    private static void register(final Map<String, FontOverrideSpec> specs,
                                 final FontOverrideSpec spec) {
        specs.put(spec.originalFontPath(), spec);
    }

    /**
     * victor 族成员清单。汉化只为族内部分成员（10/14）烘焙了 CJK 字形，
     * 12/16/21 的源 fnt 是纯 Latin-1——生成覆盖时以族内其他成员为字库捐赠者补齐。
     */
    private static final List<String> VICTOR_FAMILY = List.of(
            "graphics/fonts/victor10.fnt",
            "graphics/fonts/victor12.fnt",
            "graphics/fonts/victor14.fnt",
            "graphics/fonts/victor16.fnt",
            "graphics/fonts/victor21.fnt"
    );

    /**
     * 字库捐赠者：返回与指定字体同族的其他成员路径，生成期把捐赠者持有而源 fnt
     * 缺失的码点补入烘焙字符集（典型：victor16 缺 CJK，由 victor10/14 捐赠）。
     * 非同族字体返回空表。
     */
    static List<String> charsetDonorPaths(final String resourcePath) {
        final String normalized = normalize(resourcePath);
        if (!VICTOR_FAMILY.contains(normalized)) {
            return List.of();
        }
        final List<String> donors = new ArrayList<>(VICTOR_FAMILY.size() - 1);
        for (final String member : VICTOR_FAMILY) {
            if (!member.equals(normalized)) {
                donors.add(member);
            }
        }
        return donors;
    }

    static FontOverrideSpec specForPath(final String resourcePath) {
        return OVERRIDES.get(normalize(resourcePath));
    }

    static Path resolveOriginalFontFile(final String resourcePath) throws IOException {
        final String normalized = normalize(resourcePath);
        final Path cwd = Path.of("").toAbsolutePath().normalize();
        final Path direct = cwd.resolve(normalized).normalize();
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        throw new IOException("Original font resource not found on disk: " + normalized);
    }

    private enum FontRole {
        INSIGNIA_REGULAR,
        INSIGNIA_BOLD,
        ORBITRON_REGULAR,
        ORBITRON_BOLD
    }

    record FontOverrideSpec(String originalFontPath,
                            List<String> primaryFontCandidates,
                            List<String> fallbackFontCandidates,
                            int pageWidth,
                            int pageHeight) {
        FontOverrideSpec {
            primaryFontCandidates = List.copyOf(primaryFontCandidates);
            fallbackFontCandidates = List.copyOf(fallbackFontCandidates);
        }

        String normalizedOriginalFontPath() {
            return normalize(originalFontPath);
        }

        List<String> allFontCandidates() {
            final List<String> result = new ArrayList<>(primaryFontCandidates.size() + fallbackFontCandidates.size());
            result.addAll(primaryFontCandidates);
            result.addAll(fallbackFontCandidates);
            return result;
        }

        boolean ownsResource(final String resourcePath) {
            final String normalized = normalize(resourcePath);
            if (normalized.equals(normalizedOriginalFontPath())) {
                return true;
            }

            final String atlasPrefix = atlasPrefix();
            return normalized.startsWith(atlasPrefix)
                    && normalized.toLowerCase(Locale.ROOT).endsWith(".png");
        }

        String atlasPrefix() {
            final int slash = originalFontPath.lastIndexOf('/') + 1;
            final int dot = originalFontPath.lastIndexOf('.');
            final String directory = originalFontPath.substring(0, slash);
            final String baseName = originalFontPath.substring(slash, dot);
            return directory + baseName + '_';
        }

        String pagePath(final int pageIndex) {
            return atlasPrefix() + pageIndex + ".png";
        }

        String pageFileName(final int pageIndex) {
            final String pagePath = pagePath(pageIndex);
            return pagePath.substring(pagePath.lastIndexOf('/') + 1);
        }

        Path resolveCandidate(final Path fontDir,
                              final String fileName) {
            return fontDir.resolve(fileName).normalize();
        }
    }

    record FontProfile(String name,
                       List<String> insigniaPrimary,
                       List<String> insigniaBoldPrimary,
                       List<String> orbitronRegularPrimary,
                       List<String> orbitronBoldPrimary,
                       List<String> victorPrimary,
                       List<String> victorFallback,
                       List<String> fallback,
                       List<String> boldFallback) {
        FontProfile {
            insigniaPrimary = List.copyOf(insigniaPrimary);
            insigniaBoldPrimary = List.copyOf(insigniaBoldPrimary);
            orbitronRegularPrimary = List.copyOf(orbitronRegularPrimary);
            orbitronBoldPrimary = List.copyOf(orbitronBoldPrimary);
            victorPrimary = List.copyOf(victorPrimary);
            victorFallback = List.copyOf(victorFallback);
            fallback = List.copyOf(fallback);
            boldFallback = List.copyOf(boldFallback);
        }
    }
}