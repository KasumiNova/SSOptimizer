package github.kasuminova.ssoptimizer.common.loading;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import github.kasuminova.ssoptimizer.common.loading.TextureCompressionSupport.Format;
import org.apache.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BC 压缩纹理缓存（设计：docs/design/gpu-texture-compression.md §4.4）。
 * <p>
 * 目录 {@code <mods>/ssoptimizer/cache/textures-bc/<formatTag>/v1/}（formatTag=bc7/bc3/bc1），
 * 可用 {@code -Dssoptimizer.bctexcache.dir} 覆盖根目录（覆盖后仍按 formatTag/v1 分层）。
 * <p>
 * 键 = 源字节 SHA-256 + w/h + mip 标志 + 压缩格式 + 质量档 + 编码器版本（{@link #ENCODER_VERSION}），
 * 键哈希即数据文件名；资源路径索引沿用 {@link TextureConversionCache} 的
 * path|mtime|size 指纹模式（额外记录键字段，供按路径反查）。
 * <p>
 * 落盘内容 = SSOBC 容器（见 {@link SsobcContainer}）经 zstd 二压（实测二压收益 45~60%）。
 * 写入一律 {@link AtomicFileWriter}（临时文件 + fsync + rename）；读侧损坏/指纹不匹配
 * 记 WARN 返回 miss 并删除残件。内存 LRU 模式照搬 {@link TextureConversionCache}
 * （默认 64MB，{@code ssoptimizer.bctexcache.memory.maxbytes} 覆盖）。
 */
public final class CompressedTextureCache {
    static final String DIRECTORY_PROPERTY        = "ssoptimizer.bctexcache.dir";
    static final String MEMORY_MAX_BYTES_PROPERTY = "ssoptimizer.bctexcache.memory.maxbytes";
    /** 编码器版本（键成分）：native 编码器输出语义或格式选择逻辑变更时递增，旧缓存自然失效。
     * v2：格式选择改按实际像素 alpha 内容（AlphaKind）+ BC1 引入 1-bit punch-through alpha。 */
    static final int    ENCODER_VERSION           = 2;

    private static final Logger                      LOGGER                   = Logger.getLogger(CompressedTextureCache.class);
    private static final String                      INDEX_MAGIC              = "SSOBCIDX";
    private static final int                         INDEX_VERSION            = 1;
    private static final String                      FILE_EXTENSION           = ".ssobc.zst";
    private static final String                      INDEX_EXTENSION          = ".ssobcidx";
    private static final long                        DEFAULT_MEMORY_MAX_BYTES = 64L << 20;
    private static final Map<String, Object>         LOCKS                    = new ConcurrentHashMap<>();
    private static final Object                      MEMORY_CACHE_LOCK        = new Object();
    private static final LinkedHashMap<String, SsobcContainer> MEMORY_CACHE   = new LinkedHashMap<>(16, 0.75f, true);
    private static final Map<String, ResourceIndexEntry> RESOURCE_INDEX_CACHE = new ConcurrentHashMap<>();
    private static       long                        memoryCacheBytes         = 0L;

    private CompressedTextureCache() {
    }

    /**
     * 压缩缓存键：源字节 SHA-256 + 纹理尺寸 + mip 标志 + 压缩格式 + 质量档（编码器版本在
     * {@link #keyId} 中并入）。尺寸取实际压缩输入（conversion result 的 textureWidth/Height），
     * mip 标志取上传侧的 {@code shouldGenerateMipmaps} 结论。
     * 质量档入键：路径分级质量（{@code ssoptimizer.texcompress.highQualityPaths}）或全局
     * 质量属性变化时旧条目自动 miss，无需手动清缓存。
     */
    record Key(String sourceHash,
               int width,
               int height,
               boolean mipmaps,
               Format format,
               int quality) {
    }

    static boolean isEnabled() {
        return TextureCompressionSupport.isEnabled();
    }

    /**
     * 读取压缩缓存条目：命中返回解析校验过的 {@link SsobcContainer}；
     * 未命中/损坏/与键不符返回 null（损坏条目记 WARN 并删除，下轮自动重压）。
     * <p>
     * 命中条目驻留内存 LRU，同会话再次读取零磁盘 I/O。
     */
    static SsobcContainer load(final Key key) {
        if (!isEnabled() || !isUsableKey(key)) {
            return null;
        }

        final String keyId = keyId(key);
        final SsobcContainer inMemory = lookupMemory(keyId);
        if (inMemory != null) {
            return inMemory;
        }

        final Path cacheFile = cacheFile(key, keyId);
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }

        synchronized (lockFor(keyId)) {
            final SsobcContainer cachedInMemory = lookupMemory(keyId);
            if (cachedInMemory != null) {
                return cachedInMemory;
            }
            if (!Files.isRegularFile(cacheFile)) {
                return null;
            }

            final byte[] containerBytes;
            try {
                containerBytes = zstdDecompress(Files.readAllBytes(cacheFile), maxContainerBytes(key));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[SSOptimizer] 压缩纹理缓存读取失败（" + cacheFile + "），删除并按 miss 处理", e);
                deleteQuietly(cacheFile);
                return null;
            }
            if (containerBytes == null) {
                LOGGER.warn("[SSOptimizer] 压缩纹理缓存解压长度超限（" + cacheFile + "），删除并按 miss 处理");
                deleteQuietly(cacheFile);
                return null;
            }

            final SsobcContainer container = SsobcContainer.parse(containerBytes);
            if (container == null || !matchesKey(container, key)) {
                LOGGER.warn("[SSOptimizer] 压缩纹理缓存内容与键不符（" + cacheFile + "），删除并按 miss 处理");
                deleteQuietly(cacheFile);
                return null;
            }

            rememberMemory(keyId, container);
            return container;
        }
    }

    /**
     * 写入压缩缓存条目。已存在条目直接跳过（同键内容确定性一致）。
     * 容器与键不符（native 输出异常）时记 WARN 拒绝写入；IO 失败记 ERROR 并清理残件。
     *
     * @param resourcePath      资源路径（索引与日志用）
     * @param sourceFingerprint 源文件指纹（可为 null，为 null 时跳过资源索引写入）
     */
    static void store(final Key key,
                      final byte[] containerBytes,
                      final String resourcePath,
                      final TextureConversionCache.TextureSourceFingerprint sourceFingerprint) {
        if (!isEnabled() || !isUsableKey(key) || containerBytes == null) {
            return;
        }

        final SsobcContainer container = SsobcContainer.parse(containerBytes);
        if (container == null || !matchesKey(container, key)) {
            LOGGER.warn("[SSOptimizer] native 压缩输出与键不符（" + resourcePath + "），拒绝写入缓存");
            return;
        }

        final String keyId = keyId(key);
        final Path cacheFile = cacheFile(key, keyId);
        synchronized (lockFor(keyId)) {
            if (Files.isRegularFile(cacheFile)) {
                rememberMemory(keyId, container);
                return;
            }

            try {
                AtomicFileWriter.write(cacheFile, zstdCompress(containerBytes));
                rememberMemory(keyId, container);
                storeResourceIndex(resourcePath, sourceFingerprint, key, keyId);
            } catch (IOException | RuntimeException e) {
                LOGGER.error("[SSOptimizer] Failed to write compressed texture cache entry for " + resourcePath, e);
                deleteQuietly(cacheFile);
                forgetMemory(keyId);
            }
        }
    }

    static void clearMemoryCache() {
        synchronized (MEMORY_CACHE_LOCK) {
            MEMORY_CACHE.clear();
            memoryCacheBytes = 0L;
        }
        RESOURCE_INDEX_CACHE.clear();
    }

    /**
     * 按资源路径 + 源文件指纹反查压缩缓存键（索引指纹模式照搬 TextureConversionCache）。
     *
     * @return 指纹匹配时返回键；索引缺失/指纹变更返回 null
     */
    static Key probeKeyByResourcePath(final String resourcePath,
                                      final TextureConversionCache.TextureSourceFingerprint sourceFingerprint) {
        if (!isEnabled() || resourcePath == null || resourcePath.isBlank() || sourceFingerprint == null) {
            return null;
        }

        final String normalizedPath = normalizeResourcePath(resourcePath);
        final ResourceIndexEntry indexEntry = loadResourceIndex(normalizedPath);
        if (indexEntry == null || !indexEntry.matches(sourceFingerprint)) {
            return null;
        }
        return new Key(indexEntry.sourceHash(),
                indexEntry.width(),
                indexEntry.height(),
                indexEntry.mipmaps(),
                indexEntry.format(),
                TextureCompressionScheduler.resolveQuality(normalizedPath));
    }

    /** 键哈希（数据/索引文件名）：源哈希 + 尺寸 + mip 标志 + 格式 + 质量档 + 编码器版本。 */
    static String keyId(final Key key) {
        final String material = key.sourceHash() + '|'
                + key.width() + 'x' + key.height() + '|'
                + (key.mipmaps() ? "m1" : "m0") + '|'
                + key.format().tag() + "|q" + key.quality() + "|enc" + ENCODER_VERSION;
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    private static boolean isUsableKey(final Key key) {
        return key != null
                && key.format() != null && key.format() != Format.NONE
                && key.sourceHash() != null && !key.sourceHash().isBlank()
                && key.width() > 0 && key.height() > 0;
    }

    /** 容器与键的一致性校验：格式/尺寸一致，mip 层数与键的 mip 标志一致。 */
    private static boolean matchesKey(final SsobcContainer container, final Key key) {
        if (container.format() != key.format()
                || container.width() != key.width()
                || container.height() != key.height()) {
            return false;
        }
        final int expectedLevels = key.mipmaps()
                ? SsobcContainer.fullChainLevels(key.width(), key.height())
                : 1;
        return container.levels().size() == expectedLevels;
    }

    private static int maxContainerBytes(final Key key) {
        final int mipCount = key.mipmaps()
                ? SsobcContainer.fullChainLevels(key.width(), key.height())
                : 1;
        return SsobcContainer.expectedContainerLength(
                key.format().nativeId(), key.width(), key.height(), mipCount);
    }

    private static byte[] zstdCompress(final byte[] containerBytes) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZstdOutputStream output = new ZstdOutputStream(new BufferedOutputStream(bytes))) {
            output.write(containerBytes);
            output.flush();
            output.close();
            return bytes.toByteArray();
        }
    }

    /**
     * zstd 解压（长度上限 {@code maxExpected}，超限返回 null 视为损坏——
     * 容器长度由键完全确定，超限即非本管线产物）。
     */
    private static byte[] zstdDecompress(final byte[] compressedBytes,
                                         final int maxExpected) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new ZstdInputStream(new ByteArrayInputStream(compressedBytes))))) {
            final byte[] bytes = input.readNBytes(maxExpected + 1);
            return bytes.length > maxExpected ? null : bytes;
        }
    }

    private static void storeResourceIndex(final String resourcePath,
                                           final TextureConversionCache.TextureSourceFingerprint sourceFingerprint,
                                           final Key key,
                                           final String keyId) {
        if (sourceFingerprint == null || resourcePath == null || resourcePath.isBlank()) {
            return;
        }

        final String normalizedResourcePath = normalizeResourcePath(resourcePath);
        final ResourceIndexEntry indexEntry = new ResourceIndexEntry(
                normalizedResourcePath,
                sourceFingerprint.resolvedSourcePath(),
                sourceFingerprint.lastModifiedMillis(),
                sourceFingerprint.sizeBytes(),
                key.sourceHash(),
                key.width(),
                key.height(),
                key.mipmaps(),
                key.format()
        );

        final Path indexFile = indexFile(key, normalizedResourcePath);
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
                output.writeInt(indexEntry.width());
                output.writeInt(indexEntry.height());
                output.writeBoolean(indexEntry.mipmaps());
                output.writeUTF(indexEntry.format().tag());
                output.flush();
            }
            AtomicFileWriter.write(indexFile, bytes.toByteArray());
            RESOURCE_INDEX_CACHE.put(normalizedResourcePath, indexEntry);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[SSOptimizer] 压缩纹理资源索引写入失败（" + resourcePath + "）", e);
            deleteQuietly(indexFile);
            RESOURCE_INDEX_CACHE.remove(normalizedResourcePath);
        }
    }

    private static ResourceIndexEntry loadResourceIndex(final String normalizedResourcePath) {
        final ResourceIndexEntry cached = RESOURCE_INDEX_CACHE.get(normalizedResourcePath);
        if (cached != null) {
            return cached;
        }

        // 索引按路径哈希命名，与格式无关：遍历三个格式目录找到即用
        for (final Format format : new Format[]{Format.BC7, Format.BC3, Format.BC1}) {
            final Path indexFile = indexFile(format, normalizedResourcePath);
            if (!Files.isRegularFile(indexFile)) {
                continue;
            }

            try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(indexFile)))) {
                final String magic = input.readUTF();
                final int version = input.readInt();
                final ResourceIndexEntry loaded = new ResourceIndexEntry(
                        input.readUTF(),
                        input.readUTF(),
                        input.readLong(),
                        input.readLong(),
                        input.readUTF(),
                        input.readInt(),
                        input.readInt(),
                        input.readBoolean(),
                        formatFromTag(input.readUTF())
                );
                if (!INDEX_MAGIC.equals(magic)
                        || version != INDEX_VERSION
                        || loaded.format() == null
                        || !normalizedResourcePath.equals(loaded.normalizedResourcePath())) {
                    throw new IOException("Compressed texture resource index header mismatch");
                }

                RESOURCE_INDEX_CACHE.put(normalizedResourcePath, loaded);
                return loaded;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[SSOptimizer] 压缩纹理资源索引损坏（" + indexFile + "），删除", e);
                deleteQuietly(indexFile);
                RESOURCE_INDEX_CACHE.remove(normalizedResourcePath);
                return null;
            }
        }
        return null;
    }

    private static Format formatFromTag(final String tag) {
        for (final Format format : Format.values()) {
            if (format != Format.NONE && format.tag().equals(tag)) {
                return format;
            }
        }
        return null;
    }

    private static Object lockFor(final String keyId) {
        return LOCKS.computeIfAbsent(keyId, ignored -> new Object());
    }

    private static Path cacheFile(final Key key, final String keyId) {
        final String prefix = keyId.substring(0, 2);
        return cacheDirectory(key.format()).resolve(prefix).resolve(keyId + FILE_EXTENSION);
    }

    private static Path indexFile(final Key key, final String normalizedResourcePath) {
        return indexFile(key.format(), normalizedResourcePath);
    }

    private static Path indexFile(final Format format, final String normalizedResourcePath) {
        final String pathHash = stableHash(normalizedResourcePath);
        final String prefix = pathHash.substring(0, 2);
        return cacheDirectory(format).resolve("index").resolve(prefix).resolve(pathHash + INDEX_EXTENSION);
    }

    private static Path cacheDirectory(final Format format) {
        return cacheRoot().resolve(format.tag()).resolve("v1");
    }

    private static Path cacheRoot() {
        final String override = System.getProperty(DIRECTORY_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }

        final Path modsDir = Path.of(System.getProperty("com.fs.starfarer.settings.paths.mods", "./mods"));
        return modsDir.resolve("ssoptimizer")
                      .resolve("cache")
                      .resolve("textures-bc")
                      .toAbsolutePath();
    }

    private static long maximumMemoryBytes() {
        return Math.max(0L, Long.getLong(MEMORY_MAX_BYTES_PROPERTY, DEFAULT_MEMORY_MAX_BYTES));
    }

    private static SsobcContainer lookupMemory(final String keyId) {
        synchronized (MEMORY_CACHE_LOCK) {
            return MEMORY_CACHE.get(keyId);
        }
    }

    private static void rememberMemory(final String keyId, final SsobcContainer container) {
        final long maxBytes = maximumMemoryBytes();
        final int entryBytes = container.raw().length;
        if (maxBytes <= 0L || entryBytes > maxBytes) {
            forgetMemory(keyId);
            return;
        }

        synchronized (MEMORY_CACHE_LOCK) {
            final SsobcContainer previous = MEMORY_CACHE.remove(keyId);
            if (previous != null) {
                memoryCacheBytes -= previous.raw().length;
            }

            MEMORY_CACHE.put(keyId, container);
            memoryCacheBytes += entryBytes;

            while (memoryCacheBytes > maxBytes && !MEMORY_CACHE.isEmpty()) {
                final Map.Entry<String, SsobcContainer> eldest = MEMORY_CACHE.entrySet().iterator().next();
                MEMORY_CACHE.remove(eldest.getKey());
                memoryCacheBytes -= eldest.getValue().raw().length;
            }
        }
    }

    private static void forgetMemory(final String keyId) {
        synchronized (MEMORY_CACHE_LOCK) {
            final SsobcContainer removed = MEMORY_CACHE.remove(keyId);
            if (removed != null) {
                memoryCacheBytes -= removed.raw().length;
            }
        }
    }

    private static void deleteQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("[SSOptimizer] 压缩纹理缓存残件删除失败（" + path + "）", e);
        }
    }

    private static String normalizeResourcePath(final String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return "";
        }

        final String normalized = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        return normalized.replace('\\', '/');
    }

    private static String stableHash(final String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    private record ResourceIndexEntry(String normalizedResourcePath,
                                      String resolvedSourcePath,
                                      long lastModifiedMillis,
                                      long sizeBytes,
                                      String sourceHash,
                                      int width,
                                      int height,
                                      boolean mipmaps,
                                      Format format) {
        private boolean matches(final TextureConversionCache.TextureSourceFingerprint sourceFingerprint) {
            return sourceFingerprint != null
                    && resolvedSourcePath.equals(sourceFingerprint.resolvedSourcePath())
                    && lastModifiedMillis == sourceFingerprint.lastModifiedMillis()
                    && sizeBytes == sourceFingerprint.sizeBytes();
        }
    }
}
