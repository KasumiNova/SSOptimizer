package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证延迟贴图上传的缓存 miss 恢复路径：缓存未命中时必须能通过即时读源 + 解码
 * 完成重建，重建结果应回写压缩缓存使后续绑定直接命中；源不可读/解码失败时必须
 * 返回 null（由上传路径走 1x1 白图兜底），不得以黑采样或静默失败收场。
 */
class DeferredTextureRebuildTest {
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
    void rebuildsFromSourceWhenCacheMisses() throws Exception {
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, tempDir.resolve("cache").toString());

        final Path sourceFile = tempDir.resolve("graphics/No101/weapons/worldisaster_1_hardpoint_base.png");
        Files.createDirectories(sourceFile.getParent());
        final byte[] pngBytes = encodePng(64, 64, new Color(120, 40, 200, 255));
        Files.write(sourceFile, pngBytes);

        final String sourceHash = TrackedResourceImage.computeSourceHash(pngBytes);
        assertNull(TextureConversionCache.load(sourceHash), "前置条件：空缓存目录下该条目必须 miss");

        final String resourcePath = sourceFile.toAbsolutePath().toString();
        final LazyTextureManager.ResolvedDeferredTexture resolved =
                LazyTextureManager.resolveDeferredTextureData(resourcePath, sourceHash);

        assertNotNull(resolved, "缓存 miss 后必须通过即时读源解码完成重建");
        assertEquals(sourceHash, resolved.sourceHash());
        assertEquals(64, resolved.data().imageWidth());
        assertEquals(64, resolved.data().imageHeight());
        assertTrue(resolved.data().hasAlpha());

        final TextureConversionCache.CachedTextureData cachedAfterRebuild = TextureConversionCache.load(sourceHash);
        assertNotNull(cachedAfterRebuild, "重建必须回写压缩缓存，后续绑定应直接命中");
        assertEquals(64, cachedAfterRebuild.imageWidth());
        assertEquals(64, cachedAfterRebuild.imageHeight());

        final LazyTextureManager.ResolvedDeferredTexture second =
                LazyTextureManager.resolveDeferredTextureData(resourcePath, sourceHash);
        assertNotNull(second, "回写后再次解析应直接命中缓存");
        assertEquals(sourceHash, second.sourceHash());
    }

    @Test
    void rebuildStoresUnderRebuiltHashWhenRegisteredKeyDiffers() throws Exception {
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, tempDir.resolve("cache").toString());

        final Path sourceFile = tempDir.resolve("graphics/weapons/railgun.png");
        Files.createDirectories(sourceFile.getParent());
        final byte[] pngBytes = encodePng(32, 32, new Color(30, 120, 90, 255));
        Files.write(sourceFile, pngBytes);

        final String realHash = TrackedResourceImage.computeSourceHash(pngBytes);
        final String staleHash = "stale-registered-key-not-in-cache";

        final LazyTextureManager.ResolvedDeferredTexture resolved =
                LazyTextureManager.resolveDeferredTextureData(sourceFile.toAbsolutePath().toString(), staleHash);

        assertNotNull(resolved);
        assertEquals(realHash, resolved.sourceHash(), "重建后应返回实际源字节哈希，供调用方同步 entry 键");
        assertNotNull(TextureConversionCache.load(realHash), "重建应按实际哈希回写缓存");
        assertNull(TextureConversionCache.load(staleHash), "旧登记键不应产生缓存条目");
    }

    @Test
    void returnsNullWhenSourceIsUnreadable() {
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, tempDir.resolve("cache").toString());

        final String missingPath = tempDir.resolve("graphics/missing.png").toAbsolutePath().toString();

        final LazyTextureManager.ResolvedDeferredTexture resolved =
                LazyTextureManager.resolveDeferredTextureData(missingPath, "some-hash");

        assertNull(resolved, "源不可读时必须返回 null，由上传路径走 1x1 白图兜底");
    }

    private byte[] encodePng(final int width,
                             final int height,
                             final Color fill) throws Exception {
        final BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                source.setRGB(x, y, fill.getRGB());
            }
        }

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", out), "测试 PNG 编码必须成功");
        return out.toByteArray();
    }
}
