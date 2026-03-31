package github.kasuminova.ssoptimizer.common.loading;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import org.lwjgl.BufferUtils;

import java.awt.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class TextureConversionCache {
    static final String DISABLE_PROPERTY          = "ssoptimizer.disable.texturecache";
    static final String DIRECTORY_PROPERTY        = "ssoptimizer.texturecache.dir";
    static final String MEMORY_MAX_BYTES_PROPERTY = "ssoptimizer.texturecache.memory.maxbytes";

    private static final String                        MAGIC                    = "SSOTEX";
    private static final int                           VERSION                  = 3;
    private static final String                        FILE_EXTENSION           = ".ssotex.zst";
    private static final long                          DEFAULT_MEMORY_MAX_BYTES = 64L << 20;
    private static final Map<String, Object>           LOCKS                    = new ConcurrentHashMap<>();
    private static final Object                        MEMORY_CACHE_LOCK        = new Object();
    private static final LinkedHashMap<String, byte[]> MEMORY_CACHE             = new LinkedHashMap<>(16, 0.75f, true);
    private static       long                          memoryCacheBytes         = 0L;

    private TextureConversionCache() {
    }

    static boolean isEnabled() {
        return !Boolean.getBoolean(DISABLE_PROPERTY);
    }

    static CachedTextureData load(final String sourceHash) {
        if (!isEnabled()) {
            return null;
        }

        final CachedTextureData inMemory = loadFromMemory(sourceHash);
        if (inMemory != null) {
            return inMemory;
        }

        final Path cacheFile = cacheFile(sourceHash);
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }

        synchronized (lockFor(sourceHash)) {
            final CachedTextureData cachedInMemory = loadFromMemory(sourceHash);
            if (cachedInMemory != null) {
                return cachedInMemory;
            }
            if (!Files.isRegularFile(cacheFile)) {
                return null;
            }

            try {
                final byte[] compressedBytes = Files.readAllBytes(cacheFile);
                final CachedTextureData cached = decodeCompressed(sourceHash, compressedBytes);
                rememberCompressed(sourceHash, compressedBytes);
                return cached;
            } catch (IOException | RuntimeException ignored) {
                deleteQuietly(cacheFile);
                forgetCompressed(sourceHash);
                return null;
            }
        }
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
                Files.createDirectories(cacheFile.getParent());
                final byte[] compressedBytes = encodeCompressed(image, result);
                Files.write(cacheFile, compressedBytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                rememberCompressed(image.sourceHash(), compressedBytes);
            } catch (IOException | RuntimeException ignored) {
                deleteQuietly(cacheFile);
                forgetCompressed(image.sourceHash());
            }
        }
    }

    static void clearMemoryCache() {
        synchronized (MEMORY_CACHE_LOCK) {
            MEMORY_CACHE.clear();
            memoryCacheBytes = 0L;
        }
    }

    private static Object lockFor(final String sourceHash) {
        return LOCKS.computeIfAbsent(sourceHash, ignored -> new Object());
    }

    private static CachedTextureData loadFromMemory(final String sourceHash) {
        final byte[] compressed = lookupCompressed(sourceHash);
        if (compressed == null) {
            return null;
        }

        try {
            return decodeCompressed(sourceHash, compressed);
        } catch (IOException | RuntimeException ignored) {
            forgetCompressed(sourceHash);
            return null;
        }
    }

    private static Path cacheFile(final String sourceHash) {
        final String prefix = sourceHash.substring(0, 2);
        return cacheDirectory().resolve(prefix).resolve(sourceHash + FILE_EXTENSION);
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
                      .resolve("v3")
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

    private static CachedTextureData decodeCompressed(final String sourceHash,
                                                      final byte[] compressedBytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new ZstdInputStream(new ByteArrayInputStream(compressedBytes))))) {
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
            final int textureWidth = input.readInt();
            final int textureHeight = input.readInt();
            final int averageColor = input.readInt();
            final int upperHalfColor = input.readInt();
            final int lowerHalfColor = input.readInt();
            final int bufferLength = input.readInt();
            if (bufferLength < 0) {
                throw new IOException("Texture cache buffer length is negative");
            }

            final byte[] bytes = input.readNBytes(bufferLength);
            if (bytes.length != bufferLength) {
                throw new IOException("Texture cache payload truncated");
            }

            final ByteBuffer buffer = BufferUtils.createByteBuffer(bufferLength);
            buffer.put(bytes);
            buffer.flip();
            return new CachedTextureData(
                    imageWidth,
                    imageHeight,
                    hasAlpha,
                    new TexturePixelConversionResult(
                            buffer,
                            textureWidth,
                            textureHeight,
                            new Color(averageColor, true),
                            new Color(upperHalfColor, true),
                            new Color(lowerHalfColor, true)
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

    private static void deleteQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    record CachedTextureData(int imageWidth,
                             int imageHeight,
                             boolean hasAlpha,
                             TexturePixelConversionResult conversionResult) {
    }
}