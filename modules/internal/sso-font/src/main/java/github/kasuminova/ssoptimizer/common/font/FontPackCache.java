package github.kasuminova.ssoptimizer.common.font;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import org.apache.log4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FontPackCache {
    public static final String DISABLE_PROPERTY   = "ssoptimizer.disable.fontcache";
    public static final String DIRECTORY_PROPERTY = "ssoptimizer.fontcache.dir";

    private static final String              MAGIC               = "SSOFONT";
    private static final int                 VERSION             = 11;
    private static final String              FILE_EXTENSION      = ".ssofont.zst";
    private static final long                WARN_INTERVAL_NANOS = 60_000_000_000L;
    private static final Logger              LOGGER              = Logger.getLogger(FontPackCache.class);
    private static final Map<String, Object> LOCKS               = new ConcurrentHashMap<>();
    private static final Map<String, Long>   LAST_WARNINGS       = new ConcurrentHashMap<>();

    private FontPackCache() {
    }

    public static boolean isEnabled() {
        return !Boolean.getBoolean(DISABLE_PROPERTY);
    }

    public static String buildCacheKey(final OriginalGameFontOverrides.FontOverrideSpec spec,
                                       final String sourceFontPath,
                                       final Path fontDir,
                                       final float scale) {
        final StringBuilder fingerprint = new StringBuilder(512);
        fingerprint.append("version=").append(VERSION).append('\n');
        fingerprint.append("spec=").append(spec == null ? "" : spec.normalizedOriginalFontPath()).append('\n');
        fingerprint.append("source=").append(OriginalGameFontOverrides.normalize(sourceFontPath)).append('\n');
        fingerprint.append("scaleBits=").append(Float.floatToIntBits(scale)).append('\n');
        fingerprint.append("fontDir=").append(fontDir == null ? "" : fontDir.toAbsolutePath().normalize()).append('\n');
        fingerprint.append("pageWidth=").append(spec == null ? 0 : spec.pageWidth()).append('\n');
        fingerprint.append("pageHeight=").append(spec == null ? 0 : spec.pageHeight()).append('\n');
        fingerprint.append("profile=").append(OriginalGameFontOverrides.activeProfile().name()).append('\n');
        // 缓存键必须区分 native 占位页与位图页，避免两种产物互相命中。
        fingerprint.append("engine=")
                   .append(NativeFontRasterizer.isAvailable() ? "v2-atlas" : "bitmap")
                   .append('\n');
        fingerprint.append("rasterizer=").append(NativeFontRasterizer.requestedMode()).append('\n');
        fingerprint.append("hintAA=").append(NativeFontRasterizer.describeSettings(true)).append('\n');
        fingerprint.append("hintNoAA=").append(NativeFontRasterizer.describeSettings(false)).append('\n');

        fingerprint.append("sourceFile=")
                   .append(fileFingerprint(resolveOriginalFontPath(sourceFontPath)))
                   .append('\n');
        if (spec != null && fontDir != null) {
            final var table = FontLayoutTable.forPath(spec.normalizedOriginalFontPath());
            fingerprint.append("layoutTable=").append(table == null ? "installed" : table.fingerprint()).append('\n');
            for (final String donor : OriginalGameFontOverrides.charsetDonorPaths(spec.normalizedOriginalFontPath())) {
                fingerprint.append("donor=").append(fileFingerprint(resolveOriginalFontPath(donor))).append('\n');
            }
            appendCandidateFingerprints(fingerprint, "primary", fontDir, spec.primaryFontCandidates());
            appendCandidateFingerprints(fingerprint, "fallback", fontDir, spec.fallbackFontCandidates());
        }
        return sha256Hex(fingerprint.toString());
    }

    public static TtfBmFontGenerator.GeneratedFontPack load(final String cacheKey,
                                                            final OriginalGameFontOverrides.FontOverrideSpec spec) throws IOException {
        if (!isEnabled() || cacheKey == null || cacheKey.isBlank() || spec == null) {
            return null;
        }

        final Path cacheFile = cacheFile(cacheKey);
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }

        synchronized (lockFor(cacheKey)) {
            if (!Files.isRegularFile(cacheFile)) {
                return null;
            }

            try {
                final byte[] compressed = Files.readAllBytes(cacheFile);
                return decode(cacheKey, spec, compressed);
            } catch (IOException | RuntimeException e) {
                warnThrottled("load", cacheFile.toString(),
                        "字体缓存读取或校验失败，将删除缓存并重新生成", e);
                delete(cacheFile);
                return null;
            }
        }
    }

    public static void store(final String cacheKey,
                             final OriginalGameFontOverrides.FontOverrideSpec spec,
                             final TtfBmFontGenerator.GeneratedFontPack pack) throws IOException {
        if (!isEnabled() || cacheKey == null || cacheKey.isBlank() || spec == null || pack == null) {
            return;
        }

        final Path cacheFile = cacheFile(cacheKey);
        synchronized (lockFor(cacheKey)) {
            if (Files.isRegularFile(cacheFile)) {
                return;
            }

            Files.createDirectories(cacheFile.getParent());
            try {
                Files.write(cacheFile,
                        encode(cacheKey, spec, pack),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (IOException | RuntimeException e) {
                warnThrottled("store", cacheFile.toString(),
                        "字体缓存写入失败，将清理不完整文件", e);
                delete(cacheFile);
                throw e;
            }
        }
    }

    private static void appendCandidateFingerprints(final StringBuilder fingerprint,
                                                    final String label,
                                                    final Path fontDir,
                                                    final List<String> candidates) {
        for (String candidate : candidates) {
            fingerprint.append(label)
                       .append('=')
                       .append(candidate)
                       .append('|')
                       .append(fileFingerprint(fontDir.resolve(candidate).normalize()))
                       .append('\n');
        }
    }

    private static Path resolveOriginalFontPath(final String sourceFontPath) {
        try {
            return OriginalGameFontOverrides.resolveOriginalFontFile(sourceFontPath);
        } catch (IOException e) {
            warnThrottled("resolve-source", String.valueOf(sourceFontPath),
                    "原字体路径解析失败，缓存指纹按缺失处理", e);
            return null;
        }
    }

    private static String fileFingerprint(final Path path) {
        if (path == null) {
            return "missing";
        }
        final String normalizedPath = path.toAbsolutePath().normalize().toString();
        try {
            if (!Files.isRegularFile(path)) {
                return "missing@" + normalizedPath;
            }
            return normalizedPath
                    + "|" + Files.size(path)
                    + "|" + Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            warnThrottled("fingerprint", normalizedPath,
                    "字体文件指纹读取失败，缓存键使用 unknown 标记", e);
            return "unknown@" + normalizedPath;
        }
    }

    private static String sha256Hex(final String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Object lockFor(final String cacheKey) {
        return LOCKS.computeIfAbsent(cacheKey, ignored -> new Object());
    }

    private static Path cacheFile(final String cacheKey) {
        final String prefix = cacheKey.length() >= 2 ? cacheKey.substring(0, 2) : "00";
        return cacheDirectory().resolve(prefix).resolve(cacheKey + FILE_EXTENSION);
    }

    private static Path cacheDirectory() {
        final String override = System.getProperty(DIRECTORY_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }

        final Path modsDir = Path.of(System.getProperty("com.fs.starfarer.settings.paths.mods", "./mods"));
        return modsDir.resolve("ssoptimizer")
                      .resolve("cache")
                      .resolve("fonts")
                      .resolve("zstd")
                      .resolve("v8")
                      .toAbsolutePath();
    }

    private static byte[] encode(final String cacheKey,
                                 final OriginalGameFontOverrides.FontOverrideSpec spec,
                                 final TtfBmFontGenerator.GeneratedFontPack pack) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new ZstdOutputStream(bytes)))) {
            output.writeUTF(MAGIC);
            output.writeInt(VERSION);
            output.writeUTF(cacheKey);
            output.writeUTF(spec.normalizedOriginalFontPath());
            final TtfBmFontGenerator.GenerationReport report = pack.report();
            output.writeUTF(report.backendName());
            output.writeUTF(report.backendDetails());
            writeStringList(output, report.selectedFontSources());
            writeStringList(output, report.selectedFontFaces());
            output.writeInt(report.glyphCount());
            output.writeInt(report.pageCount());
            output.writeInt(report.infoSize());
            output.writeInt(report.lineHeight());
            output.writeInt(report.base());

            output.writeInt(pack.resources().size());
            for (Map.Entry<String, byte[]> entry : pack.resources().entrySet()) {
                output.writeUTF(OriginalGameFontOverrides.normalize(entry.getKey()));
                output.writeInt(entry.getValue().length);
                output.write(entry.getValue());
            }
            output.flush();
            return bytes.toByteArray();
        }
    }

    private static TtfBmFontGenerator.GeneratedFontPack decode(final String cacheKey,
                                                               final OriginalGameFontOverrides.FontOverrideSpec spec,
                                                               final byte[] compressed) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new ZstdInputStream(new ByteArrayInputStream(compressed))))) {
            final String magic = input.readUTF();
            final int version = input.readInt();
            final String storedKey = input.readUTF();
            final String storedSpecPath = input.readUTF();
            if (!MAGIC.equals(magic)
                    || version != VERSION
                    || !cacheKey.equals(storedKey)
                    || !spec.normalizedOriginalFontPath().equals(storedSpecPath)) {
                throw new IOException("Font pack cache header mismatch");
            }

            final String backendName = input.readUTF();
            final String backendDetails = input.readUTF();
            final List<String> selectedFontSources = readStringList(input);
            final List<String> selectedFontFaces = readStringList(input);
            final int glyphCount = input.readInt();
            final int pageCount = input.readInt();
            final int infoSize = input.readInt();
            final int lineHeight = input.readInt();
            final int base = input.readInt();

            final int resourceCount = input.readInt();
            if (resourceCount < 0) {
                throw new IOException("Negative font resource count");
            }

            final Map<String, byte[]> resources = new LinkedHashMap<>(resourceCount);
            for (int index = 0; index < resourceCount; index++) {
                final String resourcePath = input.readUTF();
                final int length = input.readInt();
                if (length < 0) {
                    throw new IOException("Negative font resource length");
                }
                final byte[] payload = input.readNBytes(length);
                if (payload.length != length) {
                    throw new IOException("Truncated font resource payload");
                }
                resources.put(resourcePath, payload);
            }

            return new TtfBmFontGenerator.GeneratedFontPack(
                    resources,
                    new TtfBmFontGenerator.GenerationReport(
                            backendName,
                            backendDetails,
                            selectedFontSources,
                            selectedFontFaces,
                            glyphCount,
                            pageCount,
                            infoSize,
                            lineHeight,
                            base
                    )
            );
        }
    }

    private static void writeStringList(final DataOutputStream output,
                                        final List<String> values) throws IOException {
        output.writeInt(values.size());
        for (String value : values) {
            output.writeUTF(value == null ? "" : value);
        }
    }

    private static List<String> readStringList(final DataInputStream input) throws IOException {
        final int size = input.readInt();
        if (size < 0) {
            throw new IOException("Negative string list size");
        }

        final List<String> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add(input.readUTF());
        }
        return List.copyOf(values);
    }

    private static void delete(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            warnThrottled("delete", path.toString(),
                    "字体缓存删除失败，后续可能反复重建", e);
        }
    }

    private static void warnThrottled(final String operation,
                                      final String location,
                                      final String message,
                                      final Throwable failure) {
        final String key = operation + '|' + location;
        final long now = System.nanoTime();
        final Long previous = LAST_WARNINGS.get(key);
        if (previous != null && now - previous < WARN_INTERVAL_NANOS) {
            return;
        }
        if (previous == null) {
            if (LAST_WARNINGS.putIfAbsent(key, now) != null) {
                return;
            }
        } else if (!LAST_WARNINGS.replace(key, previous, now)) {
            return;
        }
        LOGGER.warn("[SSOptimizer] " + message + ": path=" + location + ", reason=" + failure, failure);
    }
}