package github.kasuminova.ssoptimizer.loading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private String seedTrackedConversion(final String resourcePath,
                                         final byte[] sourceBytes) {
        final BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, new Color(10, 20, 30, 255).getRGB());
        source.setRGB(1, 0, new Color(40, 50, 60, 255).getRGB());
        source.setRGB(0, 1, new Color(70, 80, 90, 255).getRGB());
        source.setRGB(1, 1, new Color(100, 110, 120, 255).getRGB());

        final String sourceHash = TrackedResourceImage.computeSourceHash(sourceBytes);
        final BufferedImage tracked = TrackedResourceImage.wrap(resourcePath, sourceHash, source);

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