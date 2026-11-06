package github.kasuminova.ssoptimizer.common.loading;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import org.apache.log4j.Logger;
import org.lwjgl.BufferUtils;

import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 缓存以源内容哈希校验；loadMetadata 不解压像素，load 才分配 DirectBuffer。
 */
public final class TextureConversionCache {
    static final String DISABLE_PROPERTY          = "ssoptimizer.disable.texturecache";
    static final String DIRECTORY_PROPERTY        = "ssoptimizer.texturecache.dir";
    static final String MEMORY_MAX_BYTES_PROPERTY = "ssoptimizer.texturecache.memory.maxbytes";
    static final String DISABLE_WARMUP_PROPERTY   = "ssoptimizer.disable.texturecache.warmup";

    private static final Logger                        LOGGER                   = Logger.getLogger(TextureConversionCache.class);
    private static final String                        MAGIC                    = "SSOTEX";
    private static final String                        INDEX_MAGIC              = "SSOTEXIDX";
    private static final int                           VERSION                  = 4;
    private static final int                           INDEX_VERSION            = 1;
    private static final String                        FILE_EXTENSION           = ".ssotex.zst";
    private static final String                        INDEX_EXTENSION          = ".ssotexidx";
    private static final long                          DEFAULT_MEMORY_MAX_BYTES = 64L << 20;
    private static final Map<String, Object>           LOCKS                    = new ConcurrentHashMap<>();
    private static final Object                        MEMORY_CACHE_LOCK        = new Object();
    private static final LinkedHashMap<String, byte[]> MEMORY_CACHE             = new LinkedHashMap<>(16, 0.75f, true);
    private static final Map<String, CachedTextureMetadata> METADATA_CACHE      = new ConcurrentHashMap<>();
    private static final Map<String, ResourceIndexEntry> RESOURCE_INDEX_CACHE   = new ConcurrentHashMap<>();
    private static final AtomicBoolean                 WARMUP_STARTED           = new AtomicBoolean(false);
    private static volatile CompletableFuture<Void>    warmupFuture             = null;
    private static       long                          memoryCacheBytes         = 0L;

    private TextureConversionCache() {
    }

    static boolean isEnabled() {
        return !Boolean.getBoolean(DISABLE_PROPERTY);
    }

    static CachedTextureData load(final String sourceHash) {
        final byte[] compressedBytes = loadCompressedBytes(sourceHash);
        if (compressedBytes == null) {
            return null;
        }

        synchronized (lockFor(sourceHash)) {
            try {
                return decodeCompressed(sourceHash, compressedBytes);
            } catch (IOException | RuntimeException e) {
                final Path cacheFile = cacheFile(sourceHash);
                logCacheWarning("Discarding corrupt texture cache entry", cacheFile.toString(), e);
                deleteWithWarning(cacheFile);
                forgetCompressed(sourceHash);
                METADATA_CACHE.remove(sourceHash);
                return null;
            }
        }
    }

    static CachedTextureMetadata loadMetadata(final String sourceHash) {
        if (!isEnabled()) {
            return null;
        }

        final CachedTextureMetadata cached = METADATA_CACHE.get(sourceHash);
        if (cached != null) {
            return cached;
        }

        final byte[] compressedBytes = loadCompressedBytes(sourceHash);
        if (compressedBytes == null) {
            return null;
        }

        synchronized (lockFor(sourceHash)) {
            try {
                final CachedTextureMetadata metadata = decodeHeader(sourceHash, compressedBytes);
                METADATA_CACHE.put(sourceHash, metadata);
                return metadata;
            } catch (IOException | RuntimeException e) {
                final Path cacheFile = cacheFile(sourceHash);
                logCacheWarning("Discarding corrupt texture cache metadata", cacheFile.toString(), e);
                deleteWithWarning(cacheFile);
                forgetCompressed(sourceHash);
                METADATA_CACHE.remove(sourceHash);
                return null;
            }
        }
    }

    private static byte[] loadCompressedBytes(final String sourceHash) {
        if (!isEnabled()) {
            return null;
        }

        final byte[] inMemory = lookupCompressed(sourceHash);
        if (inMemory != null) {
            return inMemory;
        }

        final Path cacheFile = cacheFile(sourceHash);
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }

        synchronized (lockFor(sourceHash)) {
            final byte[] cachedInMemory = lookupCompressed(sourceHash);
            if (cachedInMemory != null) {
                return cachedInMemory;
            }
            if (!Files.isRegularFile(cacheFile)) {
                return null;
            }

            try {
                final byte[] compressedBytes = Files.readAllBytes(cacheFile);
                rememberCompressed(sourceHash, compressedBytes);
                return compressedBytes;
            } catch (IOException | RuntimeException e) {
                logCacheWarning("Discarding unreadable texture cache entry", cacheFile.toString(), e);
                deleteWithWarning(cacheFile);
                forgetCompressed(sourceHash);
                METADATA_CACHE.remove(sourceHash);
                return null;
            }
        }
    }

    static ResourceMetadataHit probeMetadataByResourcePath(final String resourcePath,
                                                           final TextureSourceFingerprint sourceFingerprint) {
        if (!isEnabled() || resourcePath == null || resourcePath.isBlank() || sourceFingerprint == null) {
            return null;
        }

        final String normalizedPath = normalizeResourcePath(resourcePath);
        final ResourceIndexEntry indexEntry = loadResourceIndex(normalizedPath);
        if (indexEntry == null || !indexEntry.matches(sourceFingerprint)) {
            return null;
        }

        final CachedTextureMetadata metadata = loadMetadata(indexEntry.sourceHash());
        if (metadata == null) {
            RESOURCE_INDEX_CACHE.remove(normalizedPath);
            deleteWithWarning(indexFile(normalizedPath));
            return null;
        }

        return new ResourceMetadataHit(indexEntry.sourceHash(), sourceFingerprint.byteLength(), metadata);
    }

    static TextureSourceFingerprint probeFingerprint(final String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }

        for (String candidate : candidatePaths(resourcePath)) {
            try {
                final Path path = Path.of(candidate).toAbsolutePath().normalize();
                if (!Files.isRegularFile(path)) {
                    continue;
                }

                final long sizeBytes = Files.size(path);
                return new TextureSourceFingerprint(
                        path.toString(),
                        Files.getLastModifiedTime(path).toMillis(),
                        sizeBytes
                );
            } catch (IOException | RuntimeException e) {
                logCacheWarning("Failed to probe texture source fingerprint", candidate, e);
            }
        }

        return null;
    }

    static void store(final TrackedResourceImage image,
                      final TexturePixelConversionResult result) {
        if (!isEnabled()) {
            return;
        }

        final Path cacheFile = cacheFile(image.sourceHash());
        synchronized (lockFor(image.sourceHash())) {
            if (Files.isRegularFile(cacheFile)) {
                return;
            }

            try {
                final byte[] compressedBytes = encodeCompressed(image, result);
                AtomicFileWriter.write(cacheFile, compressedBytes);
                rememberCompressed(image.sourceHash(), compressedBytes);
                METADATA_CACHE.put(image.sourceHash(), CachedTextureMetadata.of(
                        image.getWidth(),
                        image.getHeight(),
                        image.getColorModel().hasAlpha(),
                        result));
                storeResourceIndex(image);
            } catch (IOException | RuntimeException e) {
                LOGGER.error("[SSOptimizer] Failed to write texture cache entry for " + image.resourcePath(), e);
                deleteWithWarning(cacheFile);
                forgetCompressed(image.sourceHash());
            }
        }
    }

    static void clearMemoryCache() {
        synchronized (MEMORY_CACHE_LOCK) {
            MEMORY_CACHE.clear();
            memoryCacheBytes = 0L;
        }
        METADATA_CACHE.clear();
        RESOURCE_INDEX_CACHE.clear();
    }

    public static void warmupMemoryCache() {
        if (!isEnabled()
                || Boolean.getBoolean(DISABLE_WARMUP_PROPERTY)
                || !WARMUP_STARTED.compareAndSet(false, true)) {
            return;
        }

        warmupFuture = CompletableFuture.runAsync(TextureConversionCache::doWarmup);
    }

    static boolean awaitWarmup(final long timeoutMillis) {
        final CompletableFuture<Void> future = warmupFuture;
        if (future == null) {
            return true;
        }
        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            LOGGER.warn("[SSOptimizer] Texture cache warmup timed out after " + timeoutMillis + " ms", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("[SSOptimizer] Texture cache warmup was interrupted", e);
            return false;
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.warn("[SSOptimizer] Texture cache warmup failed", cause);
            return false;
        }
    }

    static void resetWarmupForTests() {
        WARMUP_STARTED.set(false);
        warmupFuture = null;
    }

    private static void doWarmup() {
        final Path cacheDir = cacheDirectory();
        if (!Files.isDirectory(cacheDir)) {
            return;
        }

        final long maxBytes = maximumMemoryBytes();
        final long startNanos = System.nanoTime();
        int indexCount = 0;
        int dataCount = 0;
        long totalBytes = 0L;

        final Path indexDir = cacheDir.resolve("index");
        if (Files.isDirectory(indexDir)) {
            try (Stream<Path> indexPaths = Files.walk(indexDir)) {
                final List<Path> indexFiles = indexPaths
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(INDEX_EXTENSION))
                        .toList();
                for (Path indexFile : indexFiles) {
                    try {
                        preloadIndexFile(indexFile);
                        indexCount++;
                    } catch (IOException | RuntimeException e) {
                        logCacheWarning("Discarding unreadable texture cache index during warmup", indexFile.toString(), e);
                        deleteWithWarning(indexFile);
                    }
                }
            } catch (IOException e) {
                logCacheWarning("Failed to scan texture cache index directory", indexDir.toString(), e);
            }
        }

        try (Stream<Path> cachePaths = Files.walk(cacheDir)) {
            final List<Path> dataFiles = cachePaths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(FILE_EXTENSION))
                    .toList();
            for (Path dataFile : dataFiles) {
                if (totalBytes >= maxBytes) {
                    break;
                }
                try {
                    final byte[] compressed = Files.readAllBytes(dataFile);
                    final String fileName = dataFile.getFileName().toString();
                    final String sourceHash = fileName.substring(0, fileName.length() - FILE_EXTENSION.length());
                    rememberCompressed(sourceHash, compressed);
                    totalBytes += compressed.length;
                    dataCount++;
                } catch (IOException | RuntimeException e) {
                    logCacheWarning("Failed to warm texture cache entry", dataFile.toString(), e);
                }
            }
        } catch (IOException e) {
            logCacheWarning("Failed to scan texture cache directory", cacheDir.toString(), e);
        }

        final long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        LOGGER.info("[SSOptimizer] Texture cache warmup complete: "
                + dataCount + " data file(s) (" + (totalBytes >> 10) + " KiB), "
                + indexCount + " index file(s), "
                + elapsedMs + " ms");
    }

    private static void preloadIndexFile(final Path indexFile) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(indexFile)))) {
            final String magic = input.readUTF();
            final int version = input.readInt();
            final ResourceIndexEntry entry = new ResourceIndexEntry(
                    input.readUTF(),
                    input.readUTF(),
                    input.readLong(),
                    input.readLong(),
                    input.readUTF()
            );
            if (!INDEX_MAGIC.equals(magic) || version != INDEX_VERSION) {
                throw new IOException("Texture resource index header mismatch");
            }
            RESOURCE_INDEX_CACHE.put(entry.normalizedResourcePath(), entry);
        }
    }

    private static Object lockFor(final String sourceHash) {
        return LOCKS.computeIfAbsent(sourceHash, ignored -> new Object());
    }

    private static Path cacheFile(final String sourceHash) {
        final String prefix = sourceHash.substring(0, 2);
        return cacheDirectory().resolve(prefix).resolve(sourceHash + FILE_EXTENSION);
    }

    private static Path indexFile(final String normalizedResourcePath) {
        final String pathHash = stableHash(normalizedResourcePath);
        final String prefix = pathHash.substring(0, 2);
        return cacheDirectory().resolve("index").resolve(prefix).resolve(pathHash + INDEX_EXTENSION);
    }

    private static Path cacheDirectory() {
        final String override = System.getProperty(DIRECTORY_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }

        final Path modsDir = Path.of(System.getProperty("com.fs.starfarer.settings.paths.mods", "./mods"));
        return modsDir.resolve("ssoptimizer")
                      .resolve("cache")
                      .resolve("textures")
                      .resolve("zstd")
                      .resolve("v4")
                      .toAbsolutePath();
    }

    private static long maximumMemoryBytes() {
        return Math.max(0L, Long.getLong(MEMORY_MAX_BYTES_PROPERTY, DEFAULT_MEMORY_MAX_BYTES));
    }

    private static byte[] lookupCompressed(final String sourceHash) {
        synchronized (MEMORY_CACHE_LOCK) {
            return MEMORY_CACHE.get(sourceHash);
        }
    }

    private static void rememberCompressed(final String sourceHash,
                                           final byte[] compressedBytes) {
        final long maxBytes = maximumMemoryBytes();
        if (maxBytes <= 0L || compressedBytes.length > maxBytes) {
            forgetCompressed(sourceHash);
            return;
        }

        synchronized (MEMORY_CACHE_LOCK) {
            final byte[] previous = MEMORY_CACHE.remove(sourceHash);
            if (previous != null) {
                memoryCacheBytes -= previous.length;
            }

            MEMORY_CACHE.put(sourceHash, compressedBytes);
            memoryCacheBytes += compressedBytes.length;

            while (memoryCacheBytes > maxBytes && !MEMORY_CACHE.isEmpty()) {
                final Map.Entry<String, byte[]> eldest = MEMORY_CACHE.entrySet().iterator().next();
                MEMORY_CACHE.remove(eldest.getKey());
                memoryCacheBytes -= eldest.getValue().length;
            }
        }
    }

    private static void forgetCompressed(final String sourceHash) {
        synchronized (MEMORY_CACHE_LOCK) {
            final byte[] removed = MEMORY_CACHE.remove(sourceHash);
            if (removed != null) {
                memoryCacheBytes -= removed.length;
            }
        }
    }

    private static byte[] encodeCompressed(final TrackedResourceImage image,
                                           final TexturePixelConversionResult result) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new ZstdOutputStream(bytes)))) {
            final byte[] bufferBytes = copyBufferBytes(result.buffer());
            output.writeUTF(MAGIC);
            output.writeInt(VERSION);
            output.writeUTF(image.sourceHash());
            output.writeUTF(image.resourcePath());
            output.writeInt(image.getWidth());
            output.writeInt(image.getHeight());
            output.writeBoolean(image.getColorModel().hasAlpha());
            output.writeByte(result.alphaKind().ordinal());
            output.writeInt(result.textureWidth());
            output.writeInt(result.textureHeight());
            output.writeInt(result.averageColor().getRGB());
            output.writeInt(result.upperHalfColor().getRGB());
            output.writeInt(result.lowerHalfColor().getRGB());
            output.writeInt(bufferBytes.length);
            output.write(bufferBytes);
            output.flush();
            return bytes.toByteArray();
        }
    }

    private static CachedTextureMetadata decodeHeader(final String sourceHash,
                                                      final byte[] compressedBytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new ZstdInputStream(new ByteArrayInputStream(compressedBytes))))) {
            return readHeader(input, sourceHash);
        }
    }

    private static CachedTextureMetadata readHeader(final DataInputStream input,
                                                    final String sourceHash) throws IOException {
        final String magic = input.readUTF();
        final int version = input.readInt();
        final String storedHash = input.readUTF();
        input.readUTF();

        if (!MAGIC.equals(magic) || version != VERSION || !sourceHash.equals(storedHash)) {
            throw new IOException("Texture cache header mismatch");
        }

        final int imageWidth = input.readInt();
        final int imageHeight = input.readInt();
        final boolean hasAlpha = input.readBoolean();
        final AlphaKind alphaKind;
        try {
            alphaKind = AlphaKind.fromOrdinal(input.readByte());
        } catch (IllegalArgumentException e) {
            throw new IOException("Texture cache alpha kind is invalid", e);
        }
        final int textureWidth = input.readInt();
        final int textureHeight = input.readInt();
        final int averageColor = input.readInt();
        final int upperHalfColor = input.readInt();
        final int lowerHalfColor = input.readInt();
        final int bufferLength = input.readInt();
        if (bufferLength < 0) {
            throw new IOException("Texture cache buffer length is negative");
        }

        return new CachedTextureMetadata(
                imageWidth,
                imageHeight,
                hasAlpha,
                alphaKind,
                textureWidth,
                textureHeight,
                new Color(averageColor, true),
                new Color(upperHalfColor, true),
                new Color(lowerHalfColor, true),
                bufferLength
        );
    }

    private static CachedTextureData decodeCompressed(final String sourceHash,
                                                      final byte[] compressedBytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new ZstdInputStream(new ByteArrayInputStream(compressedBytes))))) {
            final CachedTextureMetadata metadata = readHeader(input, sourceHash);
            final int bufferLength = metadata.bufferLength();

            final byte[] bytes = input.readNBytes(bufferLength);
            if (bytes.length != bufferLength) {
                throw new IOException("Texture cache payload truncated");
            }

            final ByteBuffer buffer = BufferUtils.createByteBuffer(bufferLength);
            buffer.put(bytes);
            buffer.flip();
            return new CachedTextureData(
                    metadata.imageWidth(),
                    metadata.imageHeight(),
                    metadata.hasAlpha(),
                    new TexturePixelConversionResult(
                            buffer,
                            metadata.textureWidth(),
                            metadata.textureHeight(),
                            metadata.averageColor(),
                            metadata.upperHalfColor(),
                            metadata.lowerHalfColor(),
                            metadata.alphaKind()
                    )
            );
        }
    }

    private static byte[] copyBufferBytes(final ByteBuffer buffer) {
        final ByteBuffer duplicate = buffer.duplicate();
        duplicate.position(0);
        duplicate.limit(buffer.capacity());

        final byte[] bytes = new byte[duplicate.remaining()];
        duplicate.get(bytes);
        return bytes;
    }

    private static void deleteWithWarning(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[SSOptimizer] Failed to delete texture cache file at " + path + ": " + e.getMessage(), e);
        }
    }

    private static final java.util.Set<String> LOGGED_CACHE_WARNING_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // 同一动作与路径只记录首次，避免预热扫描对同一损坏条目反复刷屏；不同路径的故障各自保留首次日志。
    private static void logCacheWarning(final String action,
                                        final String path,
                                        final Exception error) {
        if (!LOGGED_CACHE_WARNING_KEYS.add(action + '|' + path)) {
            return;
        }
        LOGGER.warn("[SSOptimizer] " + action + " at " + path + ": " + error.getMessage(), error);
    }

    private static void storeResourceIndex(final TrackedResourceImage image) {
        final TextureSourceFingerprint sourceFingerprint = image.sourceFingerprint();
        if (sourceFingerprint == null) {
            return;
        }

        final String normalizedResourcePath = normalizeResourcePath(image.resourcePath());
        final ResourceIndexEntry indexEntry = new ResourceIndexEntry(
                normalizedResourcePath,
                sourceFingerprint.resolvedSourcePath(),
                sourceFingerprint.lastModifiedMillis(),
                sourceFingerprint.sizeBytes(),
                image.sourceHash()
        );

        final Path indexFile = indexFile(normalizedResourcePath);
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(bytes))) {
                output.writeUTF(INDEX_MAGIC);
                output.writeInt(INDEX_VERSION);
                output.writeUTF(indexEntry.normalizedResourcePath());
                output.writeUTF(indexEntry.resolvedSourcePath());
                output.writeLong(indexEntry.lastModifiedMillis());
                output.writeLong(indexEntry.sizeBytes());
                output.writeUTF(indexEntry.sourceHash());
                output.flush();
            }
            AtomicFileWriter.write(indexFile, bytes.toByteArray());
            RESOURCE_INDEX_CACHE.put(normalizedResourcePath, indexEntry);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[SSOptimizer] 纹理资源索引写入失败（" + image.resourcePath() + "）", e);
            deleteWithWarning(indexFile);
            RESOURCE_INDEX_CACHE.remove(normalizedResourcePath);
        }
    }

    private static ResourceIndexEntry loadResourceIndex(final String normalizedResourcePath) {
        final ResourceIndexEntry cached = RESOURCE_INDEX_CACHE.get(normalizedResourcePath);
        if (cached != null) {
            return cached;
        }

        final Path indexFile = indexFile(normalizedResourcePath);
        if (!Files.isRegularFile(indexFile)) {
            return null;
        }

        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(indexFile)))) {
            final String magic = input.readUTF();
            final int version = input.readInt();
            final ResourceIndexEntry loaded = new ResourceIndexEntry(
                    input.readUTF(),
                    input.readUTF(),
                    input.readLong(),
                    input.readLong(),
                    input.readUTF()
            );
            if (!INDEX_MAGIC.equals(magic)
                    || version != INDEX_VERSION
                    || !normalizedResourcePath.equals(loaded.normalizedResourcePath())) {
                throw new IOException("Texture resource index header mismatch");
            }

            RESOURCE_INDEX_CACHE.put(normalizedResourcePath, loaded);
            return loaded;
        } catch (IOException | RuntimeException e) {
            logCacheWarning("Discarding corrupt texture resource index", indexFile.toString(), e);
            deleteWithWarning(indexFile);
            RESOURCE_INDEX_CACHE.remove(normalizedResourcePath);
            return null;
        }
    }

    private static String normalizeResourcePath(final String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return "";
        }

        final String normalized = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        return normalized.replace('\\', '/');
    }

    private static String[] candidatePaths(final String resourcePath) {
        final String normalized = normalizeResourcePath(resourcePath);
        if (normalized.equals(resourcePath)) {
            return new String[]{resourcePath};
        }
        return new String[]{resourcePath, normalized};
    }

    private static String stableHash(final String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    record CachedTextureMetadata(int imageWidth,
                                 int imageHeight,
                                 boolean hasAlpha,
                                 AlphaKind alphaKind,
                                 int textureWidth,
                                 int textureHeight,
                                 Color averageColor,
                                 Color upperHalfColor,
                                 Color lowerHalfColor,
                                 int bufferLength) {
        static CachedTextureMetadata of(final int imageWidth,
                                        final int imageHeight,
                                        final boolean hasAlpha,
                                        final TexturePixelConversionResult result) {
            return new CachedTextureMetadata(
                    imageWidth,
                    imageHeight,
                    hasAlpha,
                    result.alphaKind(),
                    result.textureWidth(),
                    result.textureHeight(),
                    result.averageColor(),
                    result.upperHalfColor(),
                    result.lowerHalfColor(),
                    result.buffer().capacity()
            );
        }
    }

    record CachedTextureData(int imageWidth,
                             int imageHeight,
                             boolean hasAlpha,
                             TexturePixelConversionResult conversionResult) {
    }

    record ResourceMetadataHit(String sourceHash,
                               int sourceByteLength,
                               CachedTextureMetadata metadata) {
    }

    record TextureSourceFingerprint(String resolvedSourcePath,
                                    long lastModifiedMillis,
                                    long sizeBytes) {
        int byteLength() {
            return sizeBytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, sizeBytes);
        }
    }

    private record ResourceIndexEntry(String normalizedResourcePath,
                                      String resolvedSourcePath,
                                      long lastModifiedMillis,
                                      long sizeBytes,
                                      String sourceHash) {
        private boolean matches(final TextureSourceFingerprint sourceFingerprint) {
            return sourceFingerprint != null
                    && resolvedSourcePath.equals(sourceFingerprint.resolvedSourcePath())
                    && lastModifiedMillis == sourceFingerprint.lastModifiedMillis()
                    && sizeBytes == sourceFingerprint.sizeBytes();
        }
    }
}