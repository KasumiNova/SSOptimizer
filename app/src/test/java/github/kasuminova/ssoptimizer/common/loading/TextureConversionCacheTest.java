package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TextureConversionCacheTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(TextureConversionCache.DIRECTORY_PROPERTY);
        System.clearProperty(TextureConversionCache.MEMORY_MAX_BYTES_PROPERTY);
        System.clearProperty(TextureConversionCache.DISABLE_PROPERTY);
        TextureConversionCache.clearMemoryCache();
    }

    @Test
    void canReloadFromInMemoryCompressedCacheAfterDiskEntryIsDeleted() throws Exception {
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, tempDir.toString());
        System.setProperty(TextureConversionCache.MEMORY_MAX_BYTES_PROPERTY, Long.toString(1 << 20));

        final String sourceHash = seedTrackedConversion("graphics/portraits/test_captain.png", new byte[]{1, 2, 3, 4});
        final Path cacheFile = findCacheFile(sourceHash);
        Files.delete(cacheFile);

        final TextureConversionCache.CachedTextureData cached = TextureConversionCache.load(sourceHash);

        assertNotNull(cached, "Compressed in-memory cache should satisfy reload after disk eviction");
        assertEquals(2, cached.imageWidth());
        assertEquals(2, cached.imageHeight());
    }

    @Test
    void zeroMemoryBudgetDisablesInMemoryCompressedCache() throws Exception {
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, tempDir.toString());
        System.setProperty(TextureConversionCache.MEMORY_MAX_BYTES_PROPERTY, "0");

        final String sourceHash = seedTrackedConversion("graphics/portraits/test_officer.png", new byte[]{9, 8, 7, 6});
        final Path cacheFile = findCacheFile(sourceHash);
        Files.delete(cacheFile);

        assertNull(TextureConversionCache.load(sourceHash),
                "Without memory budget, cache reload should fail once the disk entry is gone");
    }

    @Test
    void canResolveCachedTextureByResourcePathFingerprint() throws Exception {
        final Path cacheDir = tempDir.resolve("cache");
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, cacheDir.toString());

        final Path sourceFile = tempDir.resolve("graphics/test_resource.png");
        Files.createDirectories(sourceFile.getParent());
        final byte[] sourceBytes = new byte[]{1, 2, 3, 4, 5, 6};
        Files.write(sourceFile, sourceBytes);

        final String sourceHash = seedTrackedConversion(sourceFile.toString(), sourceBytes, sourceFile.toString());
        final TextureConversionCache.TextureSourceFingerprint fingerprint = TextureConversionCache.probeFingerprint(sourceFile.toString());

        assertNotNull(fingerprint);

        final TextureConversionCache.ResourceMetadataHit hit =
                TextureConversionCache.probeMetadataByResourcePath(sourceFile.toString(), fingerprint);

        assertNotNull(hit, "Resource-path lookup should resolve the persisted zstd texture cache");
        assertEquals(sourceHash, hit.sourceHash());
        assertEquals(sourceBytes.length, hit.sourceByteLength());
        assertEquals(2, hit.metadata().imageWidth());
        assertEquals(2, hit.metadata().imageHeight());
    }

    @Test
    void metadataSurvivesWithoutPixelPayload() throws Exception {
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, tempDir.toString());
        System.setProperty(TextureConversionCache.MEMORY_MAX_BYTES_PROPERTY, "0");

        final String sourceHash = seedTrackedConversion("graphics/portraits/test_metadata.png", new byte[]{5, 6, 7, 8});
        Files.delete(findCacheFile(sourceHash));

        assertNull(TextureConversionCache.load(sourceHash),
                "Without memory budget and disk entry, pixel payload should be unreadable");

        final TextureConversionCache.CachedTextureMetadata metadata = TextureConversionCache.loadMetadata(sourceHash);
        assertNotNull(metadata, "Metadata index should survive independent of the pixel payload");
        assertEquals(2, metadata.imageWidth());
        assertEquals(2, metadata.imageHeight());
        assertTrue(metadata.hasAlpha());
        assertEquals(metadata.textureWidth() * metadata.textureHeight() * 4, metadata.bufferLength());
    }

    @Test
    void metadataMatchesFullyDecodedEntry() throws Exception {
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, tempDir.toString());

        final String sourceHash = seedTrackedConversion("graphics/portraits/test_consistency.png", new byte[]{1, 1, 2, 3});

        final TextureConversionCache.CachedTextureMetadata metadata = TextureConversionCache.loadMetadata(sourceHash);
        final TextureConversionCache.CachedTextureData decoded = TextureConversionCache.load(sourceHash);

        assertNotNull(metadata);
        assertNotNull(decoded);
        assertEquals(decoded.imageWidth(), metadata.imageWidth());
        assertEquals(decoded.imageHeight(), metadata.imageHeight());
        assertEquals(decoded.hasAlpha(), metadata.hasAlpha());
        assertEquals(decoded.conversionResult().textureWidth(), metadata.textureWidth());
        assertEquals(decoded.conversionResult().textureHeight(), metadata.textureHeight());
        assertEquals(decoded.conversionResult().averageColor().getRGB(), metadata.averageColor().getRGB());
        assertEquals(decoded.conversionResult().buffer().capacity(), metadata.bufferLength());
    }

    @Test
    void resourcePathLookupInvalidatesWhenFingerprintChanges() throws Exception {
        final Path cacheDir = tempDir.resolve("cache");
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, cacheDir.toString());

        final Path sourceFile = tempDir.resolve("graphics/test_resource_changed.png");
        Files.createDirectories(sourceFile.getParent());
        final byte[] originalBytes = new byte[]{9, 8, 7, 6};
        Files.write(sourceFile, originalBytes);
        seedTrackedConversion(sourceFile.toString(), originalBytes, sourceFile.toString());

        final long changedLastModified = Files.getLastModifiedTime(sourceFile).toMillis() + 1_000L;
        Files.write(sourceFile, new byte[]{9, 8, 7, 6, 5, 4, 3});
        Files.setLastModifiedTime(sourceFile, FileTime.fromMillis(changedLastModified));

        final TextureConversionCache.TextureSourceFingerprint changedFingerprint = TextureConversionCache.probeFingerprint(sourceFile.toString());
        assertNotNull(changedFingerprint);
        assertNull(TextureConversionCache.probeMetadataByResourcePath(sourceFile.toString(), changedFingerprint),
                "Changed source file metadata should invalidate the resource-path index entry");
    }

    private String seedTrackedConversion(final String resourcePath,
                                         final byte[] sourceBytes) {
        return seedTrackedConversion(resourcePath, sourceBytes, null);
    }

    private String seedTrackedConversion(final String resourcePath,
                                         final byte[] sourceBytes,
                                         final String fingerprintPath) {
        final BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, new Color(10, 20, 30, 255).getRGB());
        source.setRGB(1, 0, new Color(40, 50, 60, 255).getRGB());
        source.setRGB(0, 1, new Color(70, 80, 90, 255).getRGB());
        source.setRGB(1, 1, new Color(100, 110, 120, 255).getRGB());

        final String sourceHash = TrackedResourceImage.computeSourceHash(sourceBytes);
        final TextureConversionCache.TextureSourceFingerprint sourceFingerprint = fingerprintPath == null
                ? null
                : TextureConversionCache.probeFingerprint(fingerprintPath);
        final BufferedImage tracked = TrackedResourceImage.wrap(resourcePath, sourceHash, source, sourceFingerprint);

        TexturePixelConversionResult result = TexturePixelConverter.convert(tracked);
        assertNotNull(result);
        return sourceHash;
    }

    private Path findCacheFile(final String sourceHash) throws IOException {
        try (Stream<Path> paths = Files.walk(tempDir)) {
            return paths
                    .filter(path -> path.getFileName().toString().startsWith(sourceHash))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Missing cache file for " + sourceHash));
        }
    }
}