package github.kasuminova.ssoptimizer.loading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TexturePixelConverterTest {
    @TempDir
    Path tempDir;

    private static boolean containsCacheFile(final Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.anyMatch(path -> path.getFileName().toString().endsWith(".ssotex.zst"));
        }
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(TextureConversionCache.DIRECTORY_PROPERTY);
        System.clearProperty(TextureConversionCache.DISABLE_PROPERTY);
        System.clearProperty(TextureDimensionSupport.DISABLE_PROPERTY);
        System.clearProperty(TextureDimensionSupport.FORCE_PROPERTY);
        TextureDimensionSupport.resetCachedSupport();
    }

    @Test
    void convertsArgbImageWithVerticalFlipAndTransparentSkip() {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, new Color(255, 0, 0, 255).getRGB());
        image.setRGB(1, 0, new Color(255, 255, 255, 0).getRGB());
        image.setRGB(0, 1, new Color(0, 255, 0, 255).getRGB());
        image.setRGB(1, 1, new Color(0, 0, 255, 255).getRGB());

        TexturePixelConversionResult result = TexturePixelConverter.convert(image);
        ByteBuffer buffer = result.buffer();

        assertEquals(2, result.textureWidth());
        assertEquals(2, result.textureHeight());
        assertEquals(85, result.averageColor().getRed());
        assertEquals(85, result.averageColor().getGreen());
        assertEquals(85, result.averageColor().getBlue());

        assertEquals(0, Byte.toUnsignedInt(buffer.get(0)));
        assertEquals(255, Byte.toUnsignedInt(buffer.get(1)));
        assertEquals(0, Byte.toUnsignedInt(buffer.get(2)));
        assertEquals(255, Byte.toUnsignedInt(buffer.get(3)));

        assertEquals(0, Byte.toUnsignedInt(buffer.get(4)));
        assertEquals(0, Byte.toUnsignedInt(buffer.get(5)));
        assertEquals(255, Byte.toUnsignedInt(buffer.get(6)));
        assertEquals(255, Byte.toUnsignedInt(buffer.get(7)));

        assertEquals(255, Byte.toUnsignedInt(buffer.get(8)));
        assertEquals(0, Byte.toUnsignedInt(buffer.get(9)));
        assertEquals(0, Byte.toUnsignedInt(buffer.get(10)));
        assertEquals(255, Byte.toUnsignedInt(buffer.get(11)));

        assertEquals(0, Byte.toUnsignedInt(buffer.get(12)));
        assertEquals(0, Byte.toUnsignedInt(buffer.get(13)));
        assertEquals(0, Byte.toUnsignedInt(buffer.get(14)));
        assertEquals(0, Byte.toUnsignedInt(buffer.get(15)));
    }

    @Test
    void convertsRgbImageToThreeComponentTextureBuffer() {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, new Color(12, 34, 56).getRGB());

        TexturePixelConversionResult result = TexturePixelConverter.convert(image);
        ByteBuffer buffer = result.buffer();

        assertEquals(2, result.textureWidth());
        assertEquals(2, result.textureHeight());
        assertEquals(12, Byte.toUnsignedInt(buffer.get(0)));
        assertEquals(34, Byte.toUnsignedInt(buffer.get(1)));
        assertEquals(56, Byte.toUnsignedInt(buffer.get(2)));
    }

    @Test
    void usesSourceDimensionsWhenNpotIsForced() {
        System.setProperty(TextureDimensionSupport.FORCE_PROPERTY, "true");

        BufferedImage image = new BufferedImage(513, 129, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, new Color(1, 2, 3, 255).getRGB());

        TexturePixelConversionResult result = TexturePixelConverter.convert(image);

        assertEquals(513, result.textureWidth());
        assertEquals(129, result.textureHeight());
    }

    @Test
    void reusesPersistedTextureConversionResultForTrackedImages() throws IOException {
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, tempDir.toString());

        BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, new Color(10, 20, 30, 255).getRGB());

        TexturePixelConversionResult first = TexturePixelConverter.convert(
                TrackedResourceImage.wrap(
                        "graphics/test.png",
                        TrackedResourceImage.computeSourceHash(new byte[]{1, 2, 3, 4}),
                        source)
        );

        BufferedImage mutated = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        mutated.setRGB(0, 0, new Color(200, 210, 220, 255).getRGB());

        TexturePixelConversionResult second = TexturePixelConverter.convert(
                TrackedResourceImage.wrap(
                        "graphics/test.png",
                        TrackedResourceImage.computeSourceHash(new byte[]{1, 2, 3, 4}),
                        mutated)
        );

        assertEquals(first.textureWidth(), second.textureWidth());
        assertEquals(first.textureHeight(), second.textureHeight());
        assertEquals(first.averageColor().getRGB(), second.averageColor().getRGB());
        assertEquals(Byte.toUnsignedInt(first.buffer().get(0)), Byte.toUnsignedInt(second.buffer().get(0)));
        assertEquals(Byte.toUnsignedInt(first.buffer().get(1)), Byte.toUnsignedInt(second.buffer().get(1)));
        assertEquals(Byte.toUnsignedInt(first.buffer().get(2)), Byte.toUnsignedInt(second.buffer().get(2)));
        assertEquals(Byte.toUnsignedInt(first.buffer().get(3)), Byte.toUnsignedInt(second.buffer().get(3)));
        assertEquals(10, Byte.toUnsignedInt(second.buffer().get(0)));
        assertEquals(20, Byte.toUnsignedInt(second.buffer().get(1)));
        assertEquals(30, Byte.toUnsignedInt(second.buffer().get(2)));
        assertTrue(containsCacheFile(tempDir), "Tracked conversion should persist a zstd cache file");
    }

    @Test
    void canDisableTextureCacheForLiveReconversion() throws IOException {
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, tempDir.toString());
        System.setProperty(TextureConversionCache.DISABLE_PROPERTY, "true");

        BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, new Color(10, 20, 30, 255).getRGB());

        TexturePixelConversionResult first = TexturePixelConverter.convert(
                TrackedResourceImage.wrap(
                        "graphics/test.png",
                        TrackedResourceImage.computeSourceHash(new byte[]{9, 8, 7, 6}),
                        source)
        );

        BufferedImage mutated = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        mutated.setRGB(0, 0, new Color(200, 210, 220, 255).getRGB());

        TexturePixelConversionResult second = TexturePixelConverter.convert(
                TrackedResourceImage.wrap(
                        "graphics/test.png",
                        TrackedResourceImage.computeSourceHash(new byte[]{9, 8, 7, 6}),
                        mutated)
        );

        assertEquals(10, Byte.toUnsignedInt(first.buffer().get(0)));
        assertEquals(200, Byte.toUnsignedInt(second.buffer().get(0)));
        assertFalse(containsCacheFile(tempDir), "Disabled cache should not write persistent files");
    }
}