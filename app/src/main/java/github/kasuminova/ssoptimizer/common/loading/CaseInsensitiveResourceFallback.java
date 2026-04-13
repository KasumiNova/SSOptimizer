package github.kasuminova.ssoptimizer.common.loading;

import org.apache.log4j.Logger;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大小写不敏感资源加载回退辅助类。
 * <p>
 * 当 {@code ResourceLoader.openStream(String)} 以精确大小写查找资源失败时（常见于
 * Windows 模组在 Linux 上运行的场景），本类通过解析异常信息中的根目录列表，逐段做
 * 大小写不敏感匹配来定位实际文件。匹配成功时记录告警日志并返回正确路径的输入流，
 * 匹配失败则返回 {@code null}，由调用方继续原始异常处理。
 * <p>
 * 所有缓存均为线程安全设计，支持多线程预加载场景。
 */
public final class CaseInsensitiveResourceFallback {
    private static final Logger LOGGER = Logger.getLogger(CaseInsensitiveResourceFallback.class);

    /**
     * 已打印过告警的资源路径集合，避免同一路径重复告警。
     */
    private static final Set<String> WARNED_PATHS = ConcurrentHashMap.newKeySet();

    /**
     * 已解析的路径映射缓存。
     * <p>
     * key = 原始请求路径，value = 实际文件的绝对路径（空字符串表示确认不可解析）。
     */
    private static final Map<String, String> RESOLVED_CACHE = new ConcurrentHashMap<>();

    /**
     * 目录名大小写映射缓存。
     * <p>
     * key = 目录绝对路径，value = 该目录下所有条目的 lowercaseName → actualName 映射。
     */
    private static final Map<String, Map<String, String>> DIR_LISTING_CACHE = new ConcurrentHashMap<>();

    private CaseInsensitiveResourceFallback() {
    }

    /**
     * 尝试以大小写不敏感方式查找资源并返回输入流。
     * <p>
     * 本方法从异常消息中解析出资源根目录列表，对每个根目录逐段做大小写不敏感匹配。
     * 首次匹配成功时记录告警并缓存结果，后续相同路径直接走缓存。
     *
     * @param resourcePath 原始请求的资源路径（如 {@code graphics/ships/foo.png}）
     * @param exception    原始加载抛出的 {@link RuntimeException}，用于提取根目录列表
     * @return 匹配到的资源输入流，或 {@code null}（未找到时）
     */
    public static InputStream tryResolve(final String resourcePath, final RuntimeException exception) {
        final String normalizedPath = normalizeResourcePath(resourcePath);
        if (normalizedPath.isEmpty()) {
            return null;
        }

        // 检查缓存
        final String cached = RESOLVED_CACHE.get(normalizedPath);
        if (cached != null) {
            if (cached.isEmpty()) {
                return null; // 已确认不可解析
            }
            try {
                return new FileInputStream(cached);
            } catch (FileNotFoundException e) {
                // 缓存过期（文件被删除），清除后重试
                RESOLVED_CACHE.remove(normalizedPath);
            }
        }

        // 从异常消息中解析资源根目录
        final List<Path> roots = parseRootsFromException(exception);
        if (roots.isEmpty()) {
            // 回退：扫描游戏目录
            final List<Path> fallbackRoots = discoverRootsFallback();
            if (fallbackRoots.isEmpty()) {
                RESOLVED_CACHE.put(normalizedPath, "");
                return null;
            }
            roots.addAll(fallbackRoots);
        }

        // 逐个根目录尝试大小写不敏感解析
        for (final Path root : roots) {
            final Path resolved = resolveInsensitive(root, normalizedPath);
            if (resolved != null && Files.isRegularFile(resolved)) {
                final String actualPath = resolved.toAbsolutePath().toString();
                RESOLVED_CACHE.put(normalizedPath, actualPath);

                if (WARNED_PATHS.add(normalizedPath)) {
                    LOGGER.warn("[SSOptimizer] 资源路径大小写不匹配: 请求 [" + normalizedPath
                            + "] -> 实际 [" + root.relativize(resolved) + "] "
                            + "(模组开发者应修正路径大小写以确保跨平台兼容)");
                }

                try {
                    return new FileInputStream(actualPath);
                } catch (FileNotFoundException e) {
                    // 理论上不会发生（刚确认文件存在）
                }
            }
        }

        RESOLVED_CACHE.put(normalizedPath, ""); // 标记为不可解析
        return null;
    }

    /**
     * 从异常消息中解析资源根目录列表。
     * <p>
     * 异常消息格式为：
     * {@code Error loading [path] resource, not found in [dir1,dir2,...,CLASSPATH]}
     *
     * @param exception 原始 RuntimeException
     * @return 解析出的目录路径列表（过滤掉 CLASSPATH 和不存在的目录）
     */
    private static List<Path> parseRootsFromException(final RuntimeException exception) {
        final String message = exception.getMessage();
        if (message == null) {
            return new ArrayList<>();
        }

        final int marker = message.lastIndexOf("not found in [");
        if (marker < 0) {
            return new ArrayList<>();
        }

        final int start = marker + "not found in [".length();
        final int end = message.lastIndexOf(']');
        if (end <= start) {
            return new ArrayList<>();
        }

        final String rootList = message.substring(start, end);
        final List<Path> roots = new ArrayList<>();
        for (final String entry : rootList.split(",")) {
            final String trimmed = entry.trim();
            if (trimmed.isEmpty() || "CLASSPATH".equals(trimmed)) {
                continue;
            }
            final Path path = Path.of(trimmed);
            if (Files.isDirectory(path)) {
                roots.add(path);
            }
        }
        return roots;
    }

    /**
     * 回退根目录发现：从游戏工作目录扫描 mods/ 子目录和 starsector-core/ 目录。
     *
     * @return 发现的根目录列表
     */
    private static List<Path> discoverRootsFallback() {
        final Path gameDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        final List<Path> roots = new ArrayList<>();

        // starsector-core
        final Path core = gameDir.resolve("starsector-core");
        if (Files.isDirectory(core)) {
            roots.add(core);
        }

        // mods/ 下的每个子目录
        final Path modsDir = gameDir.resolve("mods");
        if (Files.isDirectory(modsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
                for (final Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        roots.add(entry);
                    }
                }
            } catch (IOException ignored) {
                // 无法扫描 mods 目录
            }
        }

        // 游戏根目录本身
        roots.add(gameDir);
        return roots;
    }

    /**
     * 在给定根目录下，按路径段逐级做大小写不敏感匹配。
     *
     * @param root         资源根目录
     * @param resourcePath 相对资源路径（如 {@code graphics/ships/foo.png}）
     * @return 匹配到的文件完整路径，或 {@code null}
     */
    private static Path resolveInsensitive(final Path root, final String resourcePath) {
        final String[] segments = resourcePath.replace('\\', '/').split("/");
        Path current = root;

        for (final String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }

            final Path exact = current.resolve(segment);
            if (Files.exists(exact)) {
                current = exact;
                continue;
            }

            // 大小写不敏感搜索当前目录
            final String found = findInsensitive(current, segment);
            if (found == null) {
                return null;
            }
            current = current.resolve(found);
        }

        return current;
    }

    /**
     * 在目录中查找名称大小写不敏感匹配的条目。
     * <p>
     * 使用目录列表缓存避免重复 I/O。
     *
     * @param directory 目标目录
     * @param name      要查找的文件/子目录名
     * @return 实际匹配到的名称，或 {@code null}
     */
    private static String findInsensitive(final Path directory, final String name) {
        final String dirKey = directory.toAbsolutePath().toString();
        final Map<String, String> listing = DIR_LISTING_CACHE.computeIfAbsent(dirKey, k -> {
            final Map<String, String> map = new HashMap<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (final Path entry : stream) {
                    final String entryName = entry.getFileName().toString();
                    map.put(entryName.toLowerCase(Locale.ROOT), entryName);
                }
            } catch (IOException ignored) {
                // 无法列出目录
            }
            return map;
        });

        return listing.get(name.toLowerCase(Locale.ROOT));
    }

    private static String normalizeResourcePath(final String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return "";
        }

        String normalized = resourcePath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
