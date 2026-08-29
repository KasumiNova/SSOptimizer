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

/**
 * 模组 jar 声明路径的大小写修复解析器。
 * <p>
 * 背景与 {@link CaseInsensitiveResourceFallback} 同源：Windows 生态模组（含汉化整合包）
 * 的 {@code mod_info.json} 声明 jar 文件名与磁盘实际文件名可能存在仅大小写差异，
 * Linux 大小写敏感文件系统上该 jar 会被静默跳过，模组全部 Java 类缺失
 * （典型症状：规则系统报 {@code Command [XxxCMD] not found in packages}）。<br>
 * 本解析器在启动器回填 {@code ScriptStore.jarFiles} 时介入：声明路径精确命中则原样透传
 * （零行为变化），否则从根起逐段做大小写不敏感匹配定位实际文件；
 * 匹配失败（jar 真缺失）同样原样透传，保持原版后续错误处理路径。<br>
 * 大小写修正与 jar 缺失都会记录一次性 WARN，避免同类问题再以静默形式存在。
 */
public final class CaseInsensitiveJarResolver {
    private static final Logger LOGGER = Logger.getLogger(CaseInsensitiveJarResolver.class);

    /**
     * 解析结果缓存。key = 声明路径，value = 修正后的实际路径；与声明相同表示无需修正。
     */
    private static final Map<String, String> RESOLVED_CACHE = new ConcurrentHashMap<>();

    /**
     * 目录清单缓存。key = 目录路径字符串，value = 小写条目名 → 实际条目名。
     * 模组目录在运行期不变，清单可安全常驻。
     */
    private static final Map<String, Map<String, String>> DIR_LISTING_CACHE = new ConcurrentHashMap<>();

    /** 已打印 WARN 的声明路径，避免同一 jar 重复告警。 */
    private static final Set<String> WARNED_PATHS = ConcurrentHashMap.newKeySet();

    private CaseInsensitiveJarResolver() {
    }

    /**
     * 解析声明的 jar 路径：精确存在原样返回；仅大小写不匹配时返回实际文件路径；
     * 无法定位（jar 真缺失）原样返回并记录 WARN。
     *
     * @param declaredPath {@code mod_info.json} 声明拼接出的 jar 绝对路径
     * @return 实际可用的 jar 路径或原声明路径
     */
    public static String resolve(final String declaredPath) {
        if (declaredPath == null || declaredPath.isEmpty()) {
            return declaredPath;
        }
        return RESOLVED_CACHE.computeIfAbsent(declaredPath, CaseInsensitiveJarResolver::doResolve);
    }

    /**
     * 包一层「写入时解析」的只追加视图：透过它 {@code add} 的路径会经
     * {@link #resolve} 修正后落入委托列表。用于不改写启动器循环结构的前提下
     * 介入 jarFiles 回填。
     *
     * @param delegate 真实 jarFiles 列表
     * @return 写入侧解析视图
     */
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

    /** 从根起逐段解析：精确命中直接前进，未命中查目录清单做小写匹配。 */
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
