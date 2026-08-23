package github.kasuminova.ssoptimizer.common.loading;

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

    @Test
    void detectsActualPixelAlphaContent() {
        // 无 alpha 通道（RGB）→ OPAQUE
        final BufferedImage rgb = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        assertEquals(AlphaKind.OPAQUE, TexturePixelConverter.convert(rgb).alphaKind());

        // 声明 RGBA 但 alpha 实际全 255 → OPAQUE（压缩格式选择按实际像素内容，
        // 不再被 ColorModel 声明的 alpha 通道误导）
        final BufferedImage opaqueArgb = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                opaqueArgb.setRGB(x, y, new Color(10 + x, 20 + y, 30, 255).getRGB());
            }
        }
        assertEquals(AlphaKind.OPAQUE, TexturePixelConverter.convert(opaqueArgb).alphaKind());

        // alpha 只出现 0 与 255 → BINARY（硬边镂空，可 BC1 punch-through）
        final BufferedImage binaryArgb = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        binaryArgb.setRGB(0, 0, new Color(255, 0, 0, 255).getRGB());
        binaryArgb.setRGB(1, 0, new Color(0, 255, 0, 0).getRGB());
        binaryArgb.setRGB(0, 1, new Color(0, 0, 255, 255).getRGB());
        binaryArgb.setRGB(1, 1, new Color(255, 255, 0, 255).getRGB());
        assertEquals(AlphaKind.BINARY, TexturePixelConverter.convert(binaryArgb).alphaKind());

        // alpha 含 0/255 以外的中间值 → FULL（半透明渐变，需 BC7/BC3 插值 alpha）
        final BufferedImage fullArgb = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        fullArgb.setRGB(0, 0, new Color(255, 0, 0, 128).getRGB());
        fullArgb.setRGB(1, 0, new Color(0, 255, 0, 255).getRGB());
        fullArgb.setRGB(0, 1, new Color(0, 0, 255, 255).getRGB());
        fullArgb.setRGB(1, 1, new Color(255, 255, 0, 0).getRGB());
        assertEquals(AlphaKind.FULL, TexturePixelConverter.convert(fullArgb).alphaKind());

        // 全透明（sampledPixels=0 早退路径）→ 仍如实报 BINARY
        final BufferedImage fullyTransparent = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        assertEquals(AlphaKind.BINARY, TexturePixelConverter.convert(fullyTransparent).alphaKind());
    }

    @Test
    void alphaKindOfPureFunction() {
        assertEquals(AlphaKind.OPAQUE, TexturePixelConverter.alphaKindOf(false, false, false));
        assertEquals(AlphaKind.OPAQUE, TexturePixelConverter.alphaKindOf(false, true, true));
        assertEquals(AlphaKind.OPAQUE, TexturePixelConverter.alphaKindOf(true, false, false));
        assertEquals(AlphaKind.BINARY, TexturePixelConverter.alphaKindOf(true, true, false));
        assertEquals(AlphaKind.FULL, TexturePixelConverter.alphaKindOf(true, false, true));
        assertEquals(AlphaKind.FULL, TexturePixelConverter.alphaKindOf(true, true, true));
    }
}