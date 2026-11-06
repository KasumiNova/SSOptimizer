package github.kasuminova.ssoptimizer.common.loading;

import org.apache.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CaseInsensitiveJarResolver {
    private static final Logger LOGGER = Logger.getLogger(CaseInsensitiveJarResolver.class);

    private static final Map<String, String> RESOLVED_CACHE = new ConcurrentHashMap<>();

    // 目录清单常驻缓存以模组目录在运行期不变为前提。
    private static final Map<String, Map<String, String>> DIR_LISTING_CACHE = new ConcurrentHashMap<>();

    private static final Set<String> WARNED_PATHS = ConcurrentHashMap.newKeySet();

    private CaseInsensitiveJarResolver() {
    }

    public static String resolve(final String declaredPath) {
        if (declaredPath == null || declaredPath.isEmpty()) {
            return declaredPath;
        }
        return RESOLVED_CACHE.computeIfAbsent(declaredPath, CaseInsensitiveJarResolver::doResolve);
    }

    public static List<String> resolvingView(final List<String> delegate) {
        return new AbstractList<>() {
            @Override
            public String get(final int index) {
                return delegate.get(index);
            }

            @Override
            public int size() {
                return delegate.size();
            }

            @Override
            public boolean add(final String declaredPath) {
                return delegate.add(resolve(declaredPath));
            }
        };
    }

    private static String doResolve(final String declaredPath) {
        if (Files.isRegularFile(Path.of(declaredPath))) {
            return declaredPath;
        }

        final Path normalized = Path.of(declaredPath).toAbsolutePath().normalize();
        final Path actual = resolveInsensitive(normalized);
        if (actual != null) {
            warnOnce(declaredPath, "[SSOptimizer] 模组 jar 路径大小写不匹配: 声明 [" + declaredPath
                    + "] -> 实际 [" + actual + "]（模组打包应修正 mod_info.json 的 jars 声明以兼容 Linux）");
            return actual.toString();
        }

        warnOnce(declaredPath, "[SSOptimizer] 模组 jar 声明路径不存在且无法大小写定位: [" + declaredPath
                + "]，该模组的 Java 类将不可用");
        return declaredPath;
    }

    private static Path resolveInsensitive(final Path normalized) {
        Path current = normalized.getRoot();
        if (current == null) {
            return null;
        }
        for (final Path segment : normalized) {
            final String name = segment.toString();
            final Path exact = current.resolve(name);
            if (Files.exists(exact)) {
                current = exact;
                continue;
            }
            final String actualName = dirListing(current).get(name.toLowerCase(Locale.ROOT));
            if (actualName == null) {
                return null;
            }
            current = current.resolve(actualName);
        }
        return Files.isRegularFile(current) ? current : null;
    }

    private static Map<String, String> dirListing(final Path dir) {
        return DIR_LISTING_CACHE.computeIfAbsent(dir.toString(), key -> {
            final Map<String, String> listing = new HashMap<>();
            try (var stream = Files.list(dir)) {
                stream.forEach(entry -> {
                    final String name = entry.getFileName().toString();
                    listing.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
                });
            } catch (Exception e) {
                LOGGER.warn("[SSOptimizer] 无法枚举目录以做大小写解析: " + dir, e);
            }
            return listing;
        });
    }

    private static void warnOnce(final String declaredPath, final String message) {
        if (WARNED_PATHS.add(declaredPath)) {
            LOGGER.warn(message);
        }
    }
}
