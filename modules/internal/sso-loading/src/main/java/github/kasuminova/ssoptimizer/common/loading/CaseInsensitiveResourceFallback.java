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
 * 大小写不敏感匹配来定位实际文件。真正发生大小写修正时记录 WARN 告警；全段精确命中
 * （游戏失败原因为过滤状态干扰等）只记录一次性 INFO。匹配失败则返回 {@code null}，
 * 由调用方继续原始异常处理。
 * <p>
 * 所有缓存均为线程安全设计，支持多线程预加载场景。
 */
public final class CaseInsensitiveResourceFallback {
    private static final Logger LOGGER = Logger.getLogger(CaseInsensitiveResourceFallback.class);

    /**
     * 已打印过日志（WARN 或 INFO）的资源路径集合，避免同一路径重复输出。
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
     * 异常消息不含游戏根目录（ABSOLUTE_AND_CWD 规格不写根路径），故无条件追加
     * 游戏工作目录作为最低优先级根目录。首次匹配成功时记录告警并缓存结果，
     * 后续相同路径直接走缓存。
     * <p>
     * 隔离语义（{@code sourceFilter} / {@code suppressCustomResources}）：原版
     * {@code openResource(String, boolean)} 的「读取即清除」消费发生在异常抛出之前，
     * 且异常消息的根目录列表<b>不过滤</b>（var4 累积全部规格路径，filter 只门控是否
     * 尝试打开）——兜底通道若直接消费该列表，会把「只允许看某个模组目录」的隔离加载
     * 跨模组解析到按名称排序在前的其他模组同名文件（ASTD 实机回归）。因此调用方在
     * 进入原始加载前捕获这两个状态传入：
     * <ul>
     *   <li>{@code sourceFilter != null}：只在路径 {@code endsWith(sourceFilter)} 的
     *       模组根内做大小写修正（与原版 {@code var5.path.endsWith(var3)} 判据一致），
     *       且不追加游戏根目录（filter 生效时原版不搜 ABSOLUTE_AND_CWD）；</li>
     *   <li>{@code suppressCustomResources}：排除 {@code mods/} 下的全部模组根，
     *       只保留游戏本体根（对应原版「只加载 vanilla 资源」语义）；</li>
     *   <li>受限调用完全绕过 {@link #RESOLVED_CACHE}：缓存键只有路径，不带隔离上下文，
     *       非受限结果流入受限调用即跨模组泄漏（反之亦然）；</li>
     *   <li>受限未命中静默返回 {@code null}（隔离内不存在是正常结果，不打日志、
     *       不写「不可解析」缓存——否则同一资源的后续非受限调用会被错误短路）。</li>
     * </ul>
     *
     * @param resourcePath             原始请求的资源路径（如 {@code graphics/ships/foo.png}）
     * @param exception                原始加载抛出的 {@link RuntimeException}，用于提取根目录列表
     * @param sourceFilter             本次加载的一次性 source filter（模组 id），
     *                                 {@code null} 表示不过滤
     * @param suppressCustomResources  本次加载是否处于「只加载 vanilla 资源」状态
     * @return 匹配到的资源输入流，或 {@code null}（未找到时）
     */
    public static InputStream tryResolve(final String resourcePath, final RuntimeException exception,
                                         final String sourceFilter, final boolean suppressCustomResources) {
        final String normalizedPath = normalizeResourcePath(resourcePath);
        if (normalizedPath.isEmpty()) {
            return null;
        }
        final boolean restricted = sourceFilter != null || suppressCustomResources;

        // 检查缓存（受限调用跳过：缓存键无隔离上下文，流入即跨模组泄漏）
        if (!restricted) {
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
        }

        // 从异常消息中解析资源根目录
        final List<Path> roots = parseRootsFromException(exception);
        if (roots.isEmpty()) {
            // 回退：扫描游戏目录
            final List<Path> fallbackRoots = discoverRootsFallback();
            if (fallbackRoots.isEmpty()) {
                if (!restricted) {
                    RESOLVED_CACHE.put(normalizedPath, "");
                }
                return null;
            }
            roots.addAll(fallbackRoots);
        }

        // 游戏根目录（ABSOLUTE_AND_CWD 规格）不会出现在异常消息的根目录列表里，
        // 但基础游戏资源恰恰由它提供；模组根目录优先于游戏根目录，保持原版覆盖语义。
        // filter 生效时原版不搜 ABSOLUTE_AND_CWD，对应地不追加游戏根目录。
        final Path gameDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if (sourceFilter == null && !roots.contains(gameDir)) {
            roots.add(gameDir);
        }

        // 隔离裁剪：与原版 openResource 的根目录门控逐条对应
        if (sourceFilter != null) {
            roots.removeIf(root -> !root.toAbsolutePath().normalize().toString().endsWith(sourceFilter));
        }
        if (suppressCustomResources) {
            // 模组根以启动属性为准（可指向任意绝对路径），与 TextureConversionCache 等
            // 既有调用点同一约定；相对值按游戏根解析（运行期 user.dir 即游戏目录）
            Path modsDir = Path.of(System.getProperty("com.fs.starfarer.settings.paths.mods", "mods"));
            if (!modsDir.isAbsolute()) {
                modsDir = gameDir.resolve(modsDir).normalize();
            }
            final Path modsRoot = modsDir;
            roots.removeIf(root -> root.toAbsolutePath().normalize().startsWith(modsRoot));
        }

        // 逐个根目录尝试大小写不敏感解析
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
                        // 全段精确命中：游戏原加载失败并非大小写问题
                        // （历史上多为并行加载期 filter/suppress 全局状态跨线程干扰，
                        // 线程封闭化后应基本消失；保留此 INFO 作为兜底通道的可观测性）。
                        LOGGER.info("[SSOptimizer] 资源 [" + normalizedPath
                                + "] 精确路径存在但游戏原加载失败，已由大小写兜底通道提供");
                    }
                }

                try {
                    return new FileInputStream(actualPath);
                } catch (FileNotFoundException e) {
                    // 刚确认存在的文件打开失败（TOCTOU 删除/权限变化），继续尝试其余根目录
                    LOGGER.warn("[SSOptimizer] 资源 [" + normalizedPath + "] 命中文件打开失败: "
                            + actualPath + "（" + e.getMessage() + "），继续尝试其余根目录");
                }
            }
        }

        // 受限未命中不写「不可解析」缓存（理由见方法头注释）
        if (!restricted) {
            RESOLVED_CACHE.put(normalizedPath, "");
        }
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
            } catch (IOException e) {
                LOGGER.warn("[SSOptimizer] 扫描模组目录失败: " + modsDir + "（" + e.getMessage()
                        + "），回退根目录列表不完整");
            }
        }

        // 游戏根目录本身
        roots.add(gameDir);
        return roots;
    }

    /**
     * 一次不敏感解析的结果。
     *
     * @param path          匹配到的文件完整路径
     * @param caseCorrected 是否有路径段实际走了大小写修正（实际名称与请求段大小写不同）；
     *                      false 表示全段精确命中（游戏失败原因与大小写无关）
     */
    private record ResolvedMatch(Path path, boolean caseCorrected) {
    }

    /**
     * 在给定根目录下，按路径段逐级做大小写不敏感匹配。
     *
     * @param root         资源根目录
     * @param resourcePath 相对资源路径（如 {@code graphics/ships/foo.png}）
     * @return 匹配结果，或 {@code null}
     */
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

            // 大小写不敏感搜索当前目录
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
            } catch (IOException e) {
                // 结果会被缓存（computeIfAbsent），每个目录只告警一次
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
