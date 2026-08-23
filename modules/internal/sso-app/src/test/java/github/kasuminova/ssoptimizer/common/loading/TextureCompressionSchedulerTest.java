package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.loading.TextureCompressionSupport.Format;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TextureCompressionScheduler} 行为测试。测试环境无 native 压缩库，
 * worker 启动后即停用并丢弃任务——这正好覆盖「native 缺失降级」路径；
 * 去重与质量档解析为确定性断言。
 */
class TextureCompressionSchedulerTest {
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty(CompressedTextureCache.DIRECTORY_PROPERTY, tempDir.toString());
        TextureCompressionScheduler.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TextureCompressionScheduler.resetForTests();
        System.clearProperty(CompressedTextureCache.DIRECTORY_PROPERTY);
        System.clearProperty(TextureConversionCache.DIRECTORY_PROPERTY);
        System.clearProperty(TextureCompressionScheduler.QUALITY_PROPERTY);
        System.clearProperty(TextureCompressionScheduler.HIGH_QUALITY_PATHS_PROPERTY);
        System.clearProperty(TextureCompressionScheduler.DEFERRED_PREPASS_PROPERTY);
        TextureConversionCache.clearMemoryCache();
    }

    @Test
    void sameKeyIsDeduplicatedWhileInFlight() {
        final boolean first = TextureCompressionScheduler.submit(
                "graphics/ships/kite.png", "hash-a", 64, 64, true, Format.BC7, false, rgba8(64, 64));
        final boolean second = TextureCompressionScheduler.submit(
                "graphics/ships/kite.png", "hash-a", 64, 64, true, Format.BC7, false, rgba8(64, 64));

        if (first) {
            assertFalse(second, "同键在队/在压必须跳过（去重）");
            assertEquals(1L, TextureCompressionScheduler.submittedCount());
        }
        // native 缺失时 worker 可能已停用（first=false），此时 second 同样不得入队
        assertFalse(second);
        assertTrue(TextureCompressionScheduler.awaitIdle(10_000L), "调度器应在超时前清空队列");
    }

    @Test
    void invalidPixelBufferIsRejected() {
        final ByteBuffer oddBuffer = BufferUtils.createByteBuffer(100);
        assertFalse(TextureCompressionScheduler.submit(
                "graphics/ships/kite.png", "hash-bad", 64, 64, true, Format.BC7, false, oddBuffer));
        assertTrue(TextureCompressionScheduler.awaitIdle(10_000L));
    }

    @Test
    void rgbBufferIsAcceptedAndNothingIsPersistedWithoutNative() throws IOException {
        // RGB（w*h*3）缓冲扩通道为 RGBA8，可正常投递
        TextureCompressionScheduler.submit(
                "graphics/ships/eagle.png", "hash-rgb", 64, 64, false, Format.BC3, false, rgb8(64, 64));
        TextureCompressionScheduler.submit(
                "graphics/ships/kite.png", "hash-rgba", 64, 64, true, Format.BC7, false, rgba8(64, 64));

        assertTrue(TextureCompressionScheduler.awaitIdle(10_000L));
        // 测试环境无 native 库：任务被丢弃，缓存目录不得出现产物
        try (Stream<Path> paths = Files.walk(tempDir)) {
            assertTrue(paths.noneMatch(p -> p.getFileName().toString().endsWith(".ssobc.zst")),
                    "Without native compressor no cache entry may be written");
        }
    }

    @Test
    void deferredPrepassDecodesConvertsAndStoresConversionMetadata() throws IOException {
        // 直调 prepass 像素来源（worker 内部路径）：真实 PNG 解码→转换→ssotex 落盘，
        // 元数据携带实际像素 alpha 内容；测试环境无 native 压缩库，不影响该链路
        final Path ssotexDir = tempDir.resolve("ssotex");
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, ssotexDir.toString());

        final Path png = tempDir.resolve("graphics/prepass_opaque.png");
        Files.createDirectories(png.getParent());
        final BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                image.setRGB(x, y, new Color(x * 4 % 256, y * 4 % 256, (x + y) % 256).getRGB());
            }
        }
        javax.imageio.ImageIO.write(image, "png", png.toFile());
        final String sourceHash = TrackedResourceImage.computeSourceHash(Files.readAllBytes(png));

        final CompressedTextureCache.Key key =
                new CompressedTextureCache.Key(sourceHash, 64, 64, false, Format.BC7, NativeTextureCompressor.QUALITY_NORMAL);
        final byte[] pixels = TextureCompressionScheduler.decodePrepassPixels(png.toString(), key);

        assertNotNull(pixels, "prepass 应完成 解码→转换");
        assertEquals(64 * 64 * 4, pixels.length, "转换输出应规整为 RGBA8");
        // 转换顺带落盘 ssotex（含 alphaKind 元数据），首次 bind 的兜底上传直接受益
        final TextureConversionCache.CachedTextureMetadata metadata =
                TextureConversionCache.loadMetadata(sourceHash);
        assertNotNull(metadata, "prepass 转换应写入 ssotex 缓存");
        assertEquals(AlphaKind.OPAQUE, metadata.alphaKind(), "RGB 源的实际 alpha 内容应为 OPAQUE");
    }

    @Test
    void deferredPrepassSkipsWhenSourceHashMismatches() throws IOException {
        System.setProperty(TextureConversionCache.DIRECTORY_PROPERTY, tempDir.resolve("ssotex").toString());

        final Path png = tempDir.resolve("graphics/prepass_mismatch.png");
        Files.createDirectories(png.getParent());
        javax.imageio.ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", png.toFile());

        final long failuresBefore = TextureCompressionScheduler.failedCount();
        final CompressedTextureCache.Key staleKey =
                new CompressedTextureCache.Key("stale-hash", 64, 64, false, Format.BC7, NativeTextureCompressor.QUALITY_NORMAL);
        assertNull(TextureCompressionScheduler.decodePrepassPixels(png.toString(), staleKey),
                "源哈希与登记键不一致必须跳过（源已变更，压缩旧键无意义）");
        assertEquals(failuresBefore + 1, TextureCompressionScheduler.failedCount());
    }

    @Test
    void deferredPrepassSwitchDisablesSubmission() {
        System.setProperty(TextureCompressionScheduler.DEFERRED_PREPASS_PROPERTY, "false");
        try {
            assertFalse(TextureCompressionScheduler.submitDeferredPrepass(
                    "graphics/ships/kite.png", "hash-off", 64, 64, true, Format.BC7, false),
                    "deferredPrepass=false 时不得入队");
            assertEquals(0L, TextureCompressionScheduler.submittedCount());
        } finally {
            System.clearProperty(TextureCompressionScheduler.DEFERRED_PREPASS_PROPERTY);
        }
    }

    @Test
    void parsesQualityProperty() {
        assertEquals(NativeTextureCompressor.QUALITY_FAST, TextureCompressionScheduler.parseQuality("fast"));
        assertEquals(NativeTextureCompressor.QUALITY_NORMAL, TextureCompressionScheduler.parseQuality("normal"));
        assertEquals(NativeTextureCompressor.QUALITY_HIGH, TextureCompressionScheduler.parseQuality("high"));
        assertEquals(NativeTextureCompressor.QUALITY_NORMAL, TextureCompressionScheduler.parseQuality(null));
        assertEquals(NativeTextureCompressor.QUALITY_NORMAL, TextureCompressionScheduler.parseQuality("garbage"));

        System.setProperty(TextureCompressionScheduler.QUALITY_PROPERTY, "high");
        assertEquals(NativeTextureCompressor.QUALITY_HIGH, TextureCompressionScheduler.currentQuality());
    }

    @Test
    void resolveQualityForcesHighForConfiguredPatterns() {
        System.setProperty(TextureCompressionScheduler.HIGH_QUALITY_PATHS_PROPERTY,
                "background,starscape,nebula,illustration,/fx/");
        assertEquals(NativeTextureCompressor.QUALITY_HIGH,
                TextureCompressionScheduler.resolveQuality("graphics/backgrounds/nebula01.jpg"));
        assertEquals(NativeTextureCompressor.QUALITY_HIGH,
                TextureCompressionScheduler.resolveQuality("graphics/illustrations/rat_genesis.jpg"));
        assertEquals(NativeTextureCompressor.QUALITY_HIGH,
                TextureCompressionScheduler.resolveQuality("graphics/starscape/bg.png"));
        // 模组背景（子串匹配，大小写不敏感）
        assertEquals(NativeTextureCompressor.QUALITY_HIGH,
                TextureCompressionScheduler.resolveQuality("graphics/aEP_Background/x.png"));
        // 普通舰船贴图不命中，走全局默认 normal
        assertEquals(NativeTextureCompressor.QUALITY_NORMAL,
                TextureCompressionScheduler.resolveQuality("graphics/ships/kite.png"));
        // "/fx/" 模式不误伤 "sfx/"（音效目录）
        assertEquals(NativeTextureCompressor.QUALITY_NORMAL,
                TextureCompressionScheduler.resolveQuality("graphics/sfx/explosion.png"));
    }

    @Test
    void resolveQualityDefaultsToGlobalQualityWhenPatternsEmpty() {
        // 默认 highQualityPaths 为空：背景/特效走 excludePaths 排除面而非升档
        assertEquals(NativeTextureCompressor.QUALITY_NORMAL,
                TextureCompressionScheduler.resolveQuality("graphics/backgrounds/nebula01.jpg"));
    }

    @Test
    void resolveQualityPrefersPathTierOverGlobalQuality() {
        System.setProperty(TextureCompressionScheduler.QUALITY_PROPERTY, "fast");
        System.setProperty(TextureCompressionScheduler.HIGH_QUALITY_PATHS_PROPERTY, "background");
        assertEquals(NativeTextureCompressor.QUALITY_FAST,
                TextureCompressionScheduler.resolveQuality("graphics/ships/kite.png"));
        // 路径命中仍强制 high，优先于全局档
        assertEquals(NativeTextureCompressor.QUALITY_HIGH,
                TextureCompressionScheduler.resolveQuality("graphics/backgrounds/nebula01.jpg"));
    }

    @Test
    void highQualityPathPatternsAreConfigurable() {
        System.setProperty(TextureCompressionScheduler.HIGH_QUALITY_PATHS_PROPERTY, "precious/");
        assertEquals(NativeTextureCompressor.QUALITY_HIGH,
                TextureCompressionScheduler.resolveQuality("graphics/precious/x.png"));
        // 覆盖属性后默认模式不再生效
        assertEquals(NativeTextureCompressor.QUALITY_NORMAL,
                TextureCompressionScheduler.resolveQuality("graphics/backgrounds/nebula01.jpg"));
    }

    @Test
    void qualityIsPartOfCompressionCacheKey() {
        assertNotEquals(
                CompressedTextureCache.keyId(new CompressedTextureCache.Key(
                        "hash-a", 64, 64, true, Format.BC7, NativeTextureCompressor.QUALITY_NORMAL)),
                CompressedTextureCache.keyId(new CompressedTextureCache.Key(
                        "hash-a", 64, 64, true, Format.BC7, NativeTextureCompressor.QUALITY_HIGH)),
                "质量档变更必须使旧压缩缓存条目 miss（无需手动清缓存）");
    }

    private static ByteBuffer rgba8(final int width, final int height) {
        final ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        buffer.position(0);
        buffer.limit(buffer.capacity());
        return buffer;
    }

    private static ByteBuffer rgb8(final int width, final int height) {
        final ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 3);
        buffer.position(0);
        buffer.limit(buffer.capacity());
        return buffer;
    }
}
