package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FastResourceImageDecoderTest {
    @TempDir
    Path tempDir;

    private static byte[] pngBytes(Color color) throws Exception {
        BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, color.getRGB());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "png", out);
        return out.toByteArray();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(TextureConversionCache.DIRECTORY_PROPERTY);
        System.clearProperty(TextureConversionCache.DISABLE_PROPERTY);
    }

    @Test
    void decodesPngViaNativePathWithoutLosingPixels() throws Exception {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, new Color(255, 0, 0, 255).getRGB());
        source.setRGB(1, 0, new Color(0, 255, 0, 128).getRGB());
        source.setRGB(0, 1, new Color(0, 0, 255, 255).getRGB());
        source.setRGB(1, 1, new Color(255, 255, 0, 64).getRGB());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "png", out);

        BufferedImage decoded = FastResourceImageDecoder.decode("graphics/test.png",
                new BufferedInputStream(new ByteArrayInputStream(out.toByteArray())));

        assertNotNull(decoded);
        assertEquals(source.getWidth(), decoded.getWidth());
        assertEquals(source.getHeight(), decoded.getHeight());
        assertEquals(source.getRGB(0, 0), decoded.getRGB(0, 0));
        assertEquals(source.getRGB(1, 0), decoded.getRGB(1, 0));
        assertEquals(source.getRGB(0, 1), decoded.getRGB(0, 1));
        assertEquals(source.getRGB(1, 1), decoded.getRGB(1, 1));
    }

    @Test
    void fallsBackToImageIoForBmpResources() throws Exception {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, new Color(12, 34, 56).getRGB());
        source.setRGB(1, 0, new Color(210, 220, 230).getRGB());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "bmp", out);

        BufferedImage decoded = FastResourceImageDecoder.decode("graphics/test.bmp",
                new BufferedInputStream(new ByteArrayInputStream(out.toByteArray())));

        assertNotNull(decoded);
        assertEquals(source.getWidth(), decoded.getWidth());
        assertEquals(source.getHeight(), decoded.getHeight());
        assertEquals(source.getRGB(0, 0), decoded.getRGB(0, 0));
        assertEquals(source.getRGB(1, 0), decoded.getRGB(1, 0));
    }

    @Test
    void fallsBackToImageIoWhenPngExtensionHasNonPngPayload() throws Exception {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, new Color(90, 80, 70).getRGB());
        source.setRGB(1, 0, new Color(60, 50, 40).getRGB());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "bmp", out);

        BufferedImage decoded = FastResourceImageDecoder.decode("graphics/not_really_png.png",
                new BufferedInputStream(new ByteArrayInputStream(out.toByteArray())));

        assertNotNull(decoded);
        assertEquals(source.getWidth(), decoded.getWidth());
        assertEquals(source.getHeight(), decoded.getHeight());
        assertEquals(source.getRGB(0, 0), decoded.getRGB(0, 0));
        assertEquals(source.getRGB(1, 0), decoded.getRGB(1, 0));
    }

    @Test
    void fallsBackToImageIoWhenNativeDecoderRejectsVariant() throws Exception {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, new Color(10, 20, 30, 255).getRGB());
        source.setRGB(1, 0, new Color(40, 50, 60, 128).getRGB());
        source.setRGB(0, 1, new Color(70, 80, 90, 255).getRGB());
        source.setRGB(1, 1, new Color(100, 110, 120, 64).getRGB());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "png", out);

        BufferedImage decoded = FastResourceImageDecoder.decode(
                "graphics/unsupported_variant.png",
                new BufferedInputStream(new ByteArrayInputStream(out.toByteArray())),
                imageBytes -> {
                    throw new UnsupportedOperationException("native decoder cannot handle this PNG variant");
                }
        );

        assertNotNull(decoded);
        assertEquals(source.getWidth(), decoded.getWidth());
        assertEquals(source.getHeight(), decoded.getHeight());
        assertEquals(source.getRGB(0, 0), decoded.getRGB(0, 0));
        assertEquals(source.getRGB(1, 0), decoded.getRGB(1, 0));
        assertEquals(source.getRGB(0, 1), decoded.getRGB(0, 1));
        assertEquals(source.getRGB(1, 1), decoded.getRGB(1, 1));
    }

    @Test
    void usesNativeDecoderWhenAvailable() throws Exception {
        byte[] imageBytes = pngBytes(new Color(15, 25, 35, 255));
        BufferedImage expected = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        expected.setRGB(0, 0, new Color(111, 122, 133, 144).getRGB());

        BufferedImage decoded = FastResourceImageDecoder.decode(
                "graphics/native_first.png",
                new BufferedInputStream(new ByteArrayInputStream(imageBytes)),
                bytes -> expected
        );

        assertEquals(expected.getWidth(), decoded.getWidth());
        assertEquals(expected.getHeight(), decoded.getHeight());
        assertEquals(expected.getRGB(0, 0), decoded.getRGB(0, 0));
    }

    @Test
    void fallsBackToImageIoWhenNativeDecoderIsUnavailable() throws Exception {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, new Color(45, 55, 65, 200).getRGB());
        source.setRGB(1, 0, new Color(145, 25, 95, 80).getRGB());
        source.setRGB(0, 1, new Color(10, 120, 220, 255).getRGB());
        source.setRGB(1, 1, new Color(200, 150, 100, 50).getRGB());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "png", out);

        BufferedImage decoded = FastResourceImageDecoder.decode(
                "graphics/native_rejected.png",
                new BufferedInputStream(new ByteArrayInputStream(out.toByteArray())),
                bytes -> {
                    throw new UnsupportedOperationException("native backend unavailable");
                }
        );

        assertNotNull(decoded);
        assertEquals(source.getWidth(), decoded.getWidth());
        assertEquals(source.getHeight(), decoded.getHeight());
        assertEquals(source.getRGB(0, 0), decoded.getRGB(0, 0));
        assertEquals(source.getRGB(1, 0), decoded.getRGB(1, 0));
        assertEquals(source.getRGB(0, 1), decoded.getRGB(0, 1));
        assertEquals(source.getRGB(1, 1), decoded.getRGB(1, 1));
    }

    @Test
    void usesPersistedTextureCacheBeforeNativeDecoder() throws Exception {
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, tempDir.toString());

        BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, new Color(33, 66, 99, 255).getRGB());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "png", out);
        byte[] imageBytes = out.toByteArray();

        TexturePixelConversionResult seeded = TexturePixelConverter.convert(
                TrackedResourceImage.wrap(
                        "graphics/cached.png",
                        TrackedResourceImage.computeSourceHash(imageBytes),
                        source)
        );

        BufferedImage decoded = FastResourceImageDecoder.decode(
                "graphics/cached.png",
                new BufferedInputStream(new ByteArrayInputStream(imageBytes)),
                bytes -> {
                    throw new AssertionError("native decoder should be skipped on cache hit");
                }
        );

        assertNotNull(decoded);
        assertEquals(source.getWidth(), decoded.getWidth());
        assertEquals(source.getHeight(), decoded.getHeight());

        TexturePixelConversionResult cached = TexturePixelConverter.convert(decoded);
        ByteBuffer buffer = cached.buffer();
        assertEquals(seeded.averageColor().getRGB(), cached.averageColor().getRGB());
        assertEquals(Byte.toUnsignedInt(seeded.buffer().get(0)), Byte.toUnsignedInt(buffer.get(0)));
        assertEquals(Byte.toUnsignedInt(seeded.buffer().get(1)), Byte.toUnsignedInt(buffer.get(1)));
        assertEquals(Byte.toUnsignedInt(seeded.buffer().get(2)), Byte.toUnsignedInt(buffer.get(2)));
        assertEquals(Byte.toUnsignedInt(seeded.buffer().get(3)), Byte.toUnsignedInt(buffer.get(3)));
    }

    @Test
    void usesResourcePathIndexBeforeTouchingProvidedStream() throws Exception {
        final Path cacheDir = tempDir.resolve("cache");
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, cacheDir.toString());

        // 指纹解析走游戏 ResourceLoader 合并视图（与运行期 resolveManagedFile 一致），
        // 因此把 sourceRoot 登记为资源根，让相对资源路径可解析到磁盘文件。
        final Path sourceRoot = tempDir.resolve("modroot");
        final Path sourceFile = sourceRoot.resolve("graphics/direct_index.png");
        Files.createDirectories(sourceFile.getParent());

        final byte[] imageBytes = pngBytes(new Color(77, 88, 99, 255));
        Files.write(sourceFile, imageBytes);
        com.fs.util.ResourceLoader.getInstance().addDirectoryResource(sourceRoot.toString());

        final String resourcePath = "graphics/direct_index.png";
        final TextureConversionCache.TextureSourceFingerprint sourceFingerprint =
                LazyTextureManager.probeManagedFingerprint(resourcePath, resourcePath);
        assertNotNull(sourceFingerprint);

        final BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));

        TexturePixelConversionResult seeded = TexturePixelConverter.convert(
                TrackedResourceImage.wrap(
                        resourcePath,
                        TrackedResourceImage.computeSourceHash(imageBytes),
                        source,
                        sourceFingerprint)
        );

        BufferedImage decoded = FastResourceImageDecoder.decode(
                resourcePath,
                new InputStream() {
                    @Override
                    public int read() {
                        throw new AssertionError("cached resource-path hit should bypass the original input stream");
                    }
                },
                bytes -> {
                    throw new AssertionError("native decoder should also be skipped on direct resource-path cache hit");
                }
        );

        assertNotNull(decoded);
        TexturePixelConversionResult cached = TexturePixelConverter.convert(decoded);
        ByteBuffer buffer = cached.buffer();
        assertEquals(seeded.averageColor().getRGB(), cached.averageColor().getRGB());
        assertEquals(Byte.toUnsignedInt(seeded.buffer().get(0)), Byte.toUnsignedInt(buffer.get(0)));
        assertEquals(Byte.toUnsignedInt(seeded.buffer().get(1)), Byte.toUnsignedInt(buffer.get(1)));
        assertEquals(Byte.toUnsignedInt(seeded.buffer().get(2)), Byte.toUnsignedInt(buffer.get(2)));
        assertEquals(Byte.toUnsignedInt(seeded.buffer().get(3)), Byte.toUnsignedInt(buffer.get(3)));
    }

    /**
     * 回归：模组以同相对路径文件覆盖原版贴图时，按原版文件指纹落盘的缓存索引
     * 不得命中（指纹必须取自合并视图解析出的模组文件），解码必须读取调用方
     * 提供的模组字节流。
     */
    @Test
    void modOverrideFileInvalidatesStaleVanillaFingerprintIndex() throws Exception {
        final Path cacheDir = tempDir.resolve("cache");
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, cacheDir.toString());

        final Path coreRoot = tempDir.resolve("core");
        final Path modRoot = tempDir.resolve("mod");
        final Path coreFile = coreRoot.resolve("graphics/override.png");
        final Path modFile = modRoot.resolve("graphics/override.png");
        Files.createDirectories(coreFile.getParent());
        Files.createDirectories(modFile.getParent());

        final byte[] coreBytes = pngBytes(new Color(10, 20, 30, 255));
        final byte[] modBytes = pngBytes(new Color(200, 210, 220, 255));
        Files.write(coreFile, coreBytes);
        Files.write(modFile, modBytes);

        // 模组根插入队首（高优先级），core 根追加队尾——与游戏启动注册顺序一致
        com.fs.util.ResourceLoader.getInstance().addDirectoryResource(coreRoot.toString());
        com.fs.util.ResourceLoader.getInstance().addResourceSpec(modRoot.toString(), true, null);

        final String resourcePath = "graphics/override.png";

        // 指纹必须解析到模组文件
        final TextureConversionCache.TextureSourceFingerprint managedFingerprint =
                LazyTextureManager.probeManagedFingerprint(resourcePath, resourcePath);
        assertNotNull(managedFingerprint);
        assertEquals(modFile.toAbsolutePath().normalize().toString(), managedFingerprint.resolvedSourcePath());

        // 模拟旧行为遗留：按原版文件指纹落盘的缓存索引条目
        final TextureConversionCache.TextureSourceFingerprint staleCoreFingerprint =
                TextureConversionCache.probeFingerprint(coreFile.toString());
        assertNotNull(staleCoreFingerprint);
        TexturePixelConverter.convert(
                TrackedResourceImage.wrap(
                        resourcePath,
                        TrackedResourceImage.computeSourceHash(coreBytes),
                        ImageIO.read(new ByteArrayInputStream(coreBytes)),
                        staleCoreFingerprint)
        );

        // 解码：索引不得命中旧条目，必须读取调用方提供的模组字节流
        BufferedImage decoded = FastResourceImageDecoder.decode(
                resourcePath,
                new BufferedInputStream(new ByteArrayInputStream(modBytes))
        );

        assertNotNull(decoded);
        final BufferedImage modImage = ImageIO.read(new ByteArrayInputStream(modBytes));
        assertEquals(modImage.getRGB(0, 0), decoded.getRGB(0, 0));
    }
}