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
    public static final String ENABLE_PROPERTY     = "ssoptimizer.font.ttf.enable";
    public static final String FONT_DIR_PROPERTY   = "ssoptimizer.font.ttf.dir";
    public static final String DEBUG_LOG_PROPERTY  = "ssoptimizer.font.ttf.debug";
    public static final String PROFILE_PROPERTY    = "ssoptimizer.font.profile";
    public static final String EXPORT_PROPERTY     = FontArtifactExporter.EXPORT_PROPERTY;
    public static final String EXPORT_DIR_PROPERTY = FontArtifactExporter.EXPORT_DIR_PROPERTY;

    private static final Logger                        LOGGER           = Logger.getLogger(OriginalGameFontOverrides.class);
    private static final Path                          DEFAULT_FONT_DIR = Path.of("/mnt/windows/Data/FONTS");
    private static final Map<String, FontOverrideSpec> OVERRIDES        = createOverrideSpecs();
    private static final Object                        LOCK             = new Object();

    private static volatile Map<String, byte[]>  generatedResources           = Collections.emptyMap();
    private static volatile Map<String, Integer> generatedScaleBuckets        = Collections.emptyMap();
    private static volatile boolean              initializationAttempted      = false;
    private static volatile int                  initializedScaleBucketMillis = 0;

    private OriginalGameFontOverrides() {
    }

    public static InputStream openStream(final String resourcePath) {
        if (!isEnabled()) {
            return null;
        }

        final String normalized = normalize(resourcePath);
        final InputStream runtimeStream = RuntimeScaledFontCache.openGeneratedStream(normalized);
        if (runtimeStream != null) {
            if (Boolean.getBoolean(DEBUG_LOG_PROPERTY)) {
                LOGGER.info("[SSOptimizer][FontDebug] openStream HIT (runtime): " + normalized);
            }
            return runtimeStream;
        }
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
        final int desiredScaleBucketMillis = RuntimeScaledFontCache.bucketScaleMillis(effectiveBaseGenerationScale());
        if (initializationAttempted && initializedScaleBucketMillis == desiredScaleBucketMillis) {
            return;
        }

        synchronized (LOCK) {
            final float baseGenerationScale = effectiveBaseGenerationScale();
            final int refreshedScaleBucketMillis = RuntimeScaledFontCache.bucketScaleMillis(baseGenerationScale);
            if (initializationAttempted && initializedScaleBucketMillis == refreshedScaleBucketMillis) {
                return;
            }

            if (initializationAttempted && initializedScaleBucketMillis != 0
                    && initializedScaleBucketMillis != refreshedScaleBucketMillis) {
                LOGGER.info("[SSOptimizer] Screen scale bucket changed for original font overrides: "
                        + initializedScaleBucketMillis + " -> " + refreshedScaleBucketMillis
                        + "; regenerating generated font resources");
            }
            initializationAttempted = true;

            final Map<String, byte[]> generated = new HashMap<>();
            final Map<String, Integer> scaleBuckets = new HashMap<>();
            final Path fontDir = resolveFontDir();
            LOGGER.info("[SSOptimizer] Original font override profile=" + configuredProfileName()
                    + " fontDir=" + fontDir
                    + " baseScale=" + String.format(Locale.ROOT, "%.3f", baseGenerationScale));
            for (FontOverrideSpec spec : OVERRIDES.values()) {
                try {
                    final TtfBmFontGenerator.GeneratedFontPack pack = Math.abs(baseGenerationScale - 1.0f) < 0.001f
                            ? TtfBmFontGenerator.generate(spec, fontDir)
                            : TtfBmFontGenerator.generateScaled(spec, fontDir, spec.originalFontPath(), baseGenerationScale);
                    generated.putAll(pack.resources());
                    scaleBuckets.put(spec.normalizedOriginalFontPath(), refreshedScaleBucketMillis);
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
            generatedScaleBuckets = Map.copyOf(scaleBuckets);
            initializedScaleBucketMillis = refreshedScaleBucketMillis;

            if (!generated.isEmpty()) {
                LOGGER.info("[SSOptimizer] Generated " + generated.size() + " TTF-backed original font resource(s)");
            }
        }
    }

    private static Path resolveFontDir() {
        final String configured = System.getProperty(FONT_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_FONT_DIR;
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    static Path currentFontDir() {
        return resolveFontDir();
    }

    static float effectiveBaseGenerationScale() {
        return 1.0f;
    }

    static int baseScaleBucketMillis(final String resourcePath) {
        final String normalized = normalize(resourcePath);
        final Integer cached = generatedScaleBuckets.get(normalized);
        if (cached != null) {
            return cached;
        }
        return RuntimeScaledFontCache.bucketScaleMillis(effectiveBaseGenerationScale());
    }

    private static Map<String, FontOverrideSpec> createOverrideSpecs() {
        final Map<String, FontOverrideSpec> specs = new LinkedHashMap<>();
        final FontProfile profile = resolveProfile(configuredProfileName());
        final List<String> fallback = profile.fallback();

        registerFamily(specs, profile, fallback, FontRole.INSIGNIA_REGULAR,
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
        registerFamily(specs, profile, fallback, FontRole.INSIGNIA_BOLD,
                "graphics/fonts/insignia12bold.fnt"
        );

        registerFamily(specs, profile, fallback, FontRole.ORBITRON_BOLD, // Bold 观感更好
                "graphics/fonts/orbitron10.fnt",
                "graphics/fonts/orbitron12condensed.fnt",
                "graphics/fonts/orbitron12.fnt",
                "graphics/fonts/orbitron16.fnt",
                "graphics/fonts/orbitron20.fnt",
                "graphics/fonts/orbitron20aa.fnt",
                "graphics/fonts/orbitron24aa.fnt"
        );
        registerFamily(specs, profile, fallback, FontRole.ORBITRON_BOLD,
                "graphics/fonts/orbitron12bold.fnt",
                "graphics/fonts/orbitron20bold.fnt",
                "graphics/fonts/orbitron20aabold.fnt",
                "graphics/fonts/orbitron24aabold.fnt"
        );

        register(specs, new FontOverrideSpec(
                "graphics/fonts/victor10.fnt",
                profile.victorPrimary(),
                profile.victorFallback(),
                2048,
                2048
        ));
        register(specs, new FontOverrideSpec(
                "graphics/fonts/victor14.fnt",
                profile.victorPrimary(),
                profile.victorFallback(),
                2048,
                2048
        ));

        return Collections.unmodifiableMap(specs);
    }

    static String configuredProfileName() {
        final String configured = System.getProperty(PROFILE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return "original-match";
        }
        return configured.trim().toLowerCase(Locale.ROOT);
    }

    static FontProfile resolveProfile(final String requestedProfileName) {
        final String normalized = requestedProfileName == null || requestedProfileName.isBlank()
                ? "original-match"
                : requestedProfileName.trim().toLowerCase(Locale.ROOT);

        if (Objects.equals(normalized, "maple-ui") || Objects.equals(normalized, "mapleui")) {
            return new FontProfile(
                    "maple-ui",
                    List.of("Maple UI.ttf", "MapleUI.otf"),
                    List.of("Maple UI Bold.ttf", "MapleUI-Bold.otf", "Maple UI.ttf", "MapleUI.otf"),
                    List.of("Maple UI.ttf", "MapleUI.otf"),
                    List.of("Maple UI Bold.ttf", "MapleUI-Bold.otf"),
                    List.of("Oxanium-Medium.ttf", "MiSans-Medium.ttf"),
                    List.of("MiSans-Medium.ttf", "font.ttf"),
                    List.of("MiSans-Medium.ttf", "font.ttf")
            );
        }

        return new FontProfile(
                "original-match",
                List.of("lte50549.ttf", "MiSans-Medium.ttf"),
                List.of("lte50549.ttf", "MiSans-Medium.ttf"),
                List.of("orbitron-light.ttf", "MiSans-Medium.ttf"),
                List.of("orbitron-bold.ttf", "MiSans-Medium.ttf"),
                List.of("Oxanium-Medium.ttf", "MiSans-Medium.ttf"),
                List.of("MiSans-Medium.ttf", "font.ttf"),
                List.of("MiSans-Medium.ttf", "font.ttf")
        );
    }

    private static void registerFamily(final Map<String, FontOverrideSpec> specs,
                                       final FontProfile profile,
                                       final List<String> fallback,
                                       final FontRole role,
                                       final String... originalFontPaths) {
        final List<String> primary = primaryCandidates(profile, role);
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
                       List<String> fallback) {
        FontProfile {
            insigniaPrimary = List.copyOf(insigniaPrimary);
            insigniaBoldPrimary = List.copyOf(insigniaBoldPrimary);
            orbitronRegularPrimary = List.copyOf(orbitronRegularPrimary);
            orbitronBoldPrimary = List.copyOf(orbitronBoldPrimary);
            victorPrimary = List.copyOf(victorPrimary);
            victorFallback = List.copyOf(victorFallback);
            fallback = List.copyOf(fallback);
        }
    }
}