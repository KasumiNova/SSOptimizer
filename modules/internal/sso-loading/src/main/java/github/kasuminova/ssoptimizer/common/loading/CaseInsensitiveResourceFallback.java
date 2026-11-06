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
 * 受限加载不得读写仅以资源路径为键的缓存，避免跨模组结果泄漏；受限未命中也不写负缓存。
 */
public final class CaseInsensitiveResourceFallback {
    private static final Logger LOGGER = Logger.getLogger(CaseInsensitiveResourceFallback.class);

    private static final Set<String> WARNED_PATHS = ConcurrentHashMap.newKeySet();

    private static final Map<String, String> RESOLVED_CACHE = new ConcurrentHashMap<>();

    private static final Map<String, Map<String, String>> DIR_LISTING_CACHE = new ConcurrentHashMap<>();

    private CaseInsensitiveResourceFallback() {
    }

    public static InputStream tryResolve(final String resourcePath, final RuntimeException exception,
                                         final String sourceFilter, final boolean suppressCustomResources) {
        final String normalizedPath = normalizeResourcePath(resourcePath);
        if (normalizedPath.isEmpty()) {
            return null;
        }
        final boolean restricted = sourceFilter != null || suppressCustomResources;

        if (!restricted) {
            final String cached = RESOLVED_CACHE.get(normalizedPath);
            if (cached != null) {
                if (cached.isEmpty()) {
                    return null;
                }
                try {
                    return new FileInputStream(cached);
                } catch (FileNotFoundException e) {
                    // 缓存文件打开失败时按文件已删除处理并重新解析。
                    RESOLVED_CACHE.remove(normalizedPath);
                }
            }
        }

        final List<Path> roots = parseRootsFromException(exception);
        if (roots.isEmpty()) {
            final List<Path> fallbackRoots = discoverRootsFallback();
            if (fallbackRoots.isEmpty()) {
                if (!restricted) {
                    RESOLVED_CACHE.put(normalizedPath, "");
                }
                return null;
            }
            roots.addAll(fallbackRoots);
        }

        // 游戏根目录不在异常根列表中；sourceFilter 生效时按原版语义不追加。
        final Path gameDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if (sourceFilter == null && !roots.contains(gameDir)) {
            roots.add(gameDir);
        }

        if (sourceFilter != null) {
            roots.removeIf(root -> !root.toAbsolutePath().normalize().toString().endsWith(sourceFilter));
        }
        if (suppressCustomResources) {
            Path modsDir = Path.of(System.getProperty("com.fs.starfarer.settings.paths.mods", "mods"));
            if (!modsDir.isAbsolute()) {
                modsDir = gameDir.resolve(modsDir).normalize();
            }
            final Path modsRoot = modsDir;
            roots.removeIf(root -> root.toAbsolutePath().normalize().startsWith(modsRoot));
        }

        for (final Path root : roots) {
            final ResolvedMatch match = resolveInsensitive(root, normalizedPath);
            if (match != null && Files.isRegularFile(match.path())) {
                final String actualPath = match.path().toAbsolutePath().toString();
                if (!restricted) {
                    RESOLVED_CACHE.put(normalizedPath, actualPath);
                }

                if (WARNED_PATHS.add(normalizedPath)) {
                    if (match.caseCorrected()) {
                        LOGGER.warn("[SSOptimizer] 资源路径大小写不匹配: 请求 [" + normalizedPath
                                + "] -> 实际 [" + root.relativize(match.path()) + "] "
                                + "(模组开发者应修正路径大小写以确保跨平台兼容)");
                    } else {
                        LOGGER.info("[SSOptimizer] 资源 [" + normalizedPath
                                + "] 精确路径存在但游戏原加载失败，已由大小写兜底通道提供");
                    }
                }

                try {
                    return new FileInputStream(actualPath);
                } catch (FileNotFoundException e) {
                    LOGGER.warn("[SSOptimizer] 资源 [" + normalizedPath + "] 命中文件打开失败: "
                            + actualPath + "（" + e.getMessage() + "），继续尝试其余根目录");
                }
            }
        }

        if (!restricted) {
            RESOLVED_CACHE.put(normalizedPath, "");
        }
        return null;
    }

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

    private static List<Path> discoverRootsFallback() {
        final Path gameDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        final List<Path> roots = new ArrayList<>();

        final Path core = gameDir.resolve("starsector-core");
        if (Files.isDirectory(core)) {
            roots.add(core);
        }

        final Path modsDir = gameDir.resolve("mods");
        if (Files.isDirectory(modsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
                for (final Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        roots.add(entry);
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("[SSOptimizer] 扫描模组目录失败: " + modsDir + "（" + e.getMessage()
                        + "），回退根目录列表不完整");
            }
        }

        roots.add(gameDir);
        return roots;
    }

    private record ResolvedMatch(Path path, boolean caseCorrected) {
    }

    private static ResolvedMatch resolveInsensitive(final Path root, final String resourcePath) {
        final String[] segments = resourcePath.replace('\\', '/').split("/");
        Path current = root;
        boolean caseCorrected = false;

        for (final String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }

            final Path exact = current.resolve(segment);
            if (Files.exists(exact)) {
                current = exact;
                continue;
            }

            final String found = findInsensitive(current, segment);
            if (found == null) {
                return null;
            }
            if (!found.equals(segment)) {
                caseCorrected = true;
            }
            current = current.resolve(found);
        }

        return new ResolvedMatch(current, caseCorrected);
    }

    private static String findInsensitive(final Path directory, final String name) {
        final String dirKey = directory.toAbsolutePath().toString();
        final Map<String, String> listing = DIR_LISTING_CACHE.computeIfAbsent(dirKey, k -> {
            final Map<String, String> map = new HashMap<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (final Path entry : stream) {
                    final String entryName = entry.getFileName().toString();
                    map.put(entryName.toLowerCase(Locale.ROOT), entryName);
                }
            } catch (IOException e) {
                LOGGER.warn("[SSOptimizer] 列出目录失败: " + directory + "（" + e.getMessage()
                        + "），该目录下大小写不敏感匹配不可用");
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
