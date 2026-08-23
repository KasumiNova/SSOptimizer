package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.loading.TextureCompressionSupport.Format;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CompressedTextureCache} 真实 zstd + 文件 IO 测试（模式照搬
 * {@link TextureConversionCacheTest}）：roundtrip、键敏感性、损坏 miss、
 * 指纹失效、原子落盘、内存 LRU。
 */
class CompressedTextureCacheTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(CompressedTextureCache.DIRECTORY_PROPERTY);
        System.clearProperty(CompressedTextureCache.MEMORY_MAX_BYTES_PROPERTY);
        CompressedTextureCache.clearMemoryCache();
    }

    @Test
    void storeLoadRoundtrip() {
        useTempCacheDir();

        final CompressedTextureCache.Key key =
                new CompressedTextureCache.Key("hash-ship", 64, 64, true, Format.BC7, NativeTextureCompressor.QUALITY_NORMAL);
        final byte[] container = SsobcTestContainers.buildFullChain(
                NativeTextureCompressor.FORMAT_BC7, 64, 64);
        CompressedTextureCache.store(key, container, "graphics/ships/kite.png", null);

        final SsobcContainer loaded = CompressedTextureCache.load(key);
        assertNotNull(loaded, "Stored entry should load back");
        assertEquals(Format.BC7, loaded.format());
        assertEquals(64, loaded.width());
        assertEquals(64, loaded.height());
        assertEquals(SsobcContainer.fullChainLevels(64, 64), loaded.levels().size());
        assertArrayEquals(container, loaded.raw(), "Container bytes should survive the zstd roundtrip");
    }

    @Test
    void keyFieldsAreAllSignificant() {
        useTempCacheDir();

        final byte[] container = SsobcTestContainers.build(
                NativeTextureCompressor.FORMAT_BC7, 64, 64, 1);
        CompressedTextureCache.store(
                new CompressedTextureCache.Key("hash-ship", 64, 64, false, Format.BC7, NativeTextureCompressor.QUALITY_NORMAL),
                container, "graphics/ships/kite.png", null);

        // mip 标志不同 → miss
        assertNull(CompressedTextureCache.load(
                new CompressedTextureCache.Key("hash-ship", 64, 64, true, Format.BC7, NativeTextureCompressor.QUALITY_NORMAL)));
        // 格式不同 → miss
        assertNull(CompressedTextureCache.load(
                new CompressedTextureCache.Key("hash-ship", 64, 64, false, Format.BC3, NativeTextureCompressor.QUALITY_NORMAL)));
        // 尺寸不同 → miss
        assertNull(CompressedTextureCache.load(
                new CompressedTextureCache.Key("hash-ship", 128, 64, false, Format.BC7, NativeTextureCompressor.QUALITY_NORMAL)));
        // 源哈希不同 → miss
        assertNull(CompressedTextureCache.load(
                new CompressedTextureCache.Key("hash-other", 64, 64, false, Format.BC7, NativeTextureCompressor.QUALITY_NORMAL)));
    }

    @Test
    void corruptedEntryIsDeletedAndReportedAsMiss() throws Exception {
        useTempCacheDir();
        System.setProperty(CompressedTextureCache.MEMORY_MAX_BYTES_PROPERTY, "0");

        final CompressedTextureCache.Key key =
                new CompressedTextureCache.Key("hash-corrupt", 64, 64, true, Format.BC3, NativeTextureCompressor.QUALITY_NORMAL);
        CompressedTextureCache.store(key, SsobcTestContainers.buildFullChain(
                NativeTextureCompressor.FORMAT_BC3, 64, 64), "graphics/ships/eagle.png", null);

        final Path cacheFile = findCacheFile();
        Files.write(cacheFile, new byte[]{1, 2, 3, 4, 5});

        assertNull(CompressedTextureCache.load(key), "Corrupted entry must be a miss");
        assertFalse(Files.exists(cacheFile), "Corrupted entry should be deleted for re-compression");
    }

    @Test
    void containerMismatchingKeyIsRejectedOnStore() throws Exception {
        useTempCacheDir();

        // BC7 容器配 BC3 键：拒绝写入（native 输出异常的防御）
        CompressedTextureCache.store(
                new CompressedTextureCache.Key("hash-mismatch", 64, 64, true, Format.BC3, NativeTextureCompressor.QUALITY_NORMAL),
                SsobcTestContainers.buildFullChain(NativeTextureCompressor.FORMAT_BC7, 64, 64),
                "graphics/ships/eagle.png", null);

        assertNull(CompressedTextureCache.load(
                new CompressedTextureCache.Key("hash-mismatch", 64, 64, true, Format.BC3, NativeTextureCompressor.QUALITY_NORMAL)));
        assertTrue(findCacheFiles().isEmpty(), "Mismatching container must not reach disk");
    }

    @Test
    void resourceIndexInvalidatesWhenFingerprintChanges() throws Exception {
        useTempCacheDir();

        final Path sourceFile = tempDir.resolve("graphics/kite.png");
        Files.createDirectories(sourceFile.getParent());
        Files.write(sourceFile, new byte[]{9, 8, 7, 6});

        TextureConversionCache.TextureSourceFingerprint fingerprint =
                TextureConversionCache.probeFingerprint(sourceFile.toString());
        assertNotNull(fingerprint);

        final CompressedTextureCache.Key key =
                new CompressedTextureCache.Key("hash-indexed", 64, 64, false, Format.BC1, NativeTextureCompressor.QUALITY_NORMAL);
        CompressedTextureCache.store(key, SsobcTestContainers.build(
                NativeTextureCompressor.FORMAT_BC1, 64, 64, 1), sourceFile.toString(), fingerprint);

        final CompressedTextureCache.Key probed =
                CompressedTextureCache.probeKeyByResourcePath(sourceFile.toString(), fingerprint);
        assertNotNull(probed, "Index probe should resolve the stored key");
        assertEquals(key, probed);

        // 源文件变更（大小 + mtime）→ 指纹失效
        Files.write(sourceFile, new byte[]{9, 8, 7, 6, 5, 4, 3});
        Files.setLastModifiedTime(sourceFile,
                FileTime.fromMillis(Files.getLastModifiedTime(sourceFile).toMillis() + 1_000L));
        final TextureConversionCache.TextureSourceFingerprint changed =
                TextureConversionCache.probeFingerprint(sourceFile.toString());
        assertNull(CompressedTextureCache.probeKeyByResourcePath(sourceFile.toString(), changed),
                "Changed fingerprint must invalidate the index entry");
    }

    @Test
    void atomicWriteLeavesNoTempFiles() throws Exception {
        useTempCacheDir();

        CompressedTextureCache.store(
                new CompressedTextureCache.Key("hash-atomic", 64, 64, true, Format.BC7, NativeTextureCompressor.QUALITY_NORMAL),
                SsobcTestContainers.buildFullChain(NativeTextureCompressor.FORMAT_BC7, 64, 64),
                "graphics/ships/kite.png", null);

        final List<Path> files = findAllFiles();
        assertFalse(files.isEmpty(), "Cache entry should be on disk");
        for (final Path file : files) {
            assertFalse(file.getFileName().toString().endsWith(".tmp"),
                    "Temp file residue after atomic write: " + file);
        }
        assertEquals(1, files.stream()
                .filter(p -> p.getFileName().toString().endsWith(".ssobc.zst")).count());
    }

    @Test
    void memoryLruSatisfiesLoadAfterDiskEviction() throws Exception {
        useTempCacheDir();

        final CompressedTextureCache.Key key =
                new CompressedTextureCache.Key("hash-memory", 64, 64, true, Format.BC7, NativeTextureCompressor.QUALITY_NORMAL);
        CompressedTextureCache.store(key, SsobcTestContainers.buildFullChain(
                NativeTextureCompressor.FORMAT_BC7, 64, 64), "graphics/ships/kite.png", null);
        Files.delete(findCacheFile());

        assertNotNull(CompressedTextureCache.load(key),
                "Memory LRU should satisfy reload after disk eviction");
    }

    private void useTempCacheDir() {
        System.setProperty(CompressedTextureCache.DIRECTORY_PROPERTY, tempDir.toString());
    }

    private Path findCacheFile() throws IOException {
        final List<Path> files = findCacheFiles();
        assertEquals(1, files.size(), "Expected exactly one cache data file");
        return files.get(0);
    }

    private List<Path> findCacheFiles() throws IOException {
        return findAllFiles().stream()
                .filter(p -> p.getFileName().toString().endsWith(".ssobc.zst"))
                .toList();
    }

    private List<Path> findAllFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(tempDir)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }
}
