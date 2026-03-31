package github.kasuminova.ssoptimizer.common.font;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Exports generated BMFont artifacts and a small manifest for manual A/B
 * inspection.
 */
public final class FontArtifactExporter {
    public static final String EXPORT_PROPERTY     = "ssoptimizer.font.export";
    public static final String EXPORT_DIR_PROPERTY = "ssoptimizer.font.export.dir";

    private FontArtifactExporter() {
    }

    public static boolean isEnabled() {
        final String exportDir = System.getProperty(EXPORT_DIR_PROPERTY);
        return (exportDir != null && !exportDir.isBlank())
                || Boolean.parseBoolean(System.getProperty(EXPORT_PROPERTY, "false"));
    }

    public static Path exportConfigured(final OriginalGameFontOverrides.FontOverrideSpec spec,
                                        final TtfBmFontGenerator.GeneratedFontPack pack) throws IOException {
        return exportConfigured(spec, spec.originalFontPath(), pack);
    }

    public static Path exportConfigured(final OriginalGameFontOverrides.FontOverrideSpec spec,
                                        final String originalFontPath,
                                        final TtfBmFontGenerator.GeneratedFontPack pack) throws IOException {
        if (!isEnabled() || spec == null || pack == null) {
            return null;
        }

        final Path exportRoot = exportRoot();
        Files.createDirectories(exportRoot);

        for (Map.Entry<String, byte[]> entry : pack.resources().entrySet()) {
            final Path output = exportRoot.resolve(OriginalGameFontOverrides.normalize(entry.getKey())).normalize();
            Files.createDirectories(output.getParent());
            Files.write(output, entry.getValue());
        }

        final Path manifestPath = exportRoot.resolve(manifestRelativePath(spec.normalizedOriginalFontPath())).normalize();
        Files.createDirectories(manifestPath.getParent());
        Files.writeString(manifestPath, buildManifestJson(spec, originalFontPath, pack.report()), StandardCharsets.UTF_8);
        return exportRoot;
    }

    private static Path exportRoot() {
        final String configured = System.getProperty(EXPORT_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of("")
                   .toAbsolutePath()
                   .normalize()
                   .resolve("ssoptimizer-font-export");
    }

    private static String manifestRelativePath(final String fontPath) {
        final int dot = fontPath.lastIndexOf('.');
        final String base = dot >= 0 ? fontPath.substring(0, dot) : fontPath;
        return base + ".manifest.json";
    }

    private static String buildManifestJson(final OriginalGameFontOverrides.FontOverrideSpec spec,
                                            final String originalFontPath,
                                            final TtfBmFontGenerator.GenerationReport report) {
        final StringBuilder json = new StringBuilder(768);
        json.append("{\n");
        json.append("  \"generatedAt\":\"").append(escapeJson(Instant.now().toString())).append("\",\n");
        json.append("  \"originalFontPath\":\"").append(escapeJson(OriginalGameFontOverrides.normalize(originalFontPath))).append("\",\n");
        json.append("  \"backend\":\"").append(escapeJson(report.backendName())).append("\",\n");
        json.append("  \"backendDetails\":\"").append(escapeJson(report.backendDetails())).append("\",\n");
        json.append("  \"glyphCount\":").append(report.glyphCount()).append(",\n");
        json.append("  \"pageCount\":").append(report.pageCount()).append(",\n");
        json.append("  \"infoSize\":").append(report.infoSize()).append(",\n");
        json.append("  \"lineHeight\":").append(report.lineHeight()).append(",\n");
        json.append("  \"base\":").append(report.base()).append(",\n");
        appendStringArray(json, "selectedFontSources", report.selectedFontSources()).append(",\n");
        appendStringArray(json, "selectedFontFaces", report.selectedFontFaces()).append(",\n");
        appendStringArray(json, "primaryCandidates", spec.primaryFontCandidates()).append(",\n");
        appendStringArray(json, "fallbackCandidates", spec.fallbackFontCandidates()).append('\n');
        json.append('}');
        return json.toString();
    }

    private static StringBuilder appendStringArray(final StringBuilder json,
                                                   final String fieldName,
                                                   final List<String> values) {
        json.append("  \"").append(fieldName).append("\":[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('\"').append(escapeJson(values.get(index))).append('\"');
        }
        json.append(']');
        return json;
    }

    private static String escapeJson(final String value) {
        if (value == null) {
            return "";
        }

        final StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            final char ch = value.charAt(index);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '\"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(ch);
            }
        }
        return escaped.toString();
    }
}