package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.concurrent.VtWorkers;
import org.apache.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 启动期资源索引：把游戏 ResourceLoader 的「逐 mod 目录 stat + 重复 listFiles」
 * 探测收敛为每个资源根目录一次性的全量快照。
 * <p>
 * 工作方式：每个 DIRECTORY 资源根目录在首次被查询时构建一次完整快照
 * （相对路径 → 文件元数据 + 目录 → 直接子项），之后的 exists/open/list 全部转为
 * 内存查找；同时对 {@code data/} 下的小文件做异步预读，文本类资源读取不再触碰磁盘。
 * 快照构建失败的根目录（不可读等）标记为未索引，调用方回退直接文件操作。
 * <p>
 * 线程模型（Wave 3 起）：后台任务（快照预构建、预读）提交 {@link VtWorkers} 虚拟线程。
 * 原固定池的并行上限只约束平台线程数，虚拟线程下无意义，故移除且不设闸门——
 * 并发任务数天然按资源根目录数封项（每根目录一个快照任务 + 一个串行预读任务），
 * 预读总量另有 {@code ssoptimizer.spec.prefetch.mb} 内存预算约束。
 */
public final class ResourceIndex {
    private static final Logger LOGGER = Logger.getLogger(ResourceIndex.class);

    /** 全局禁用开关：{@code -Dssoptimizer.disable.resourceindex=true} 时调用方走原版文件逻辑。 */
    public static final  String DISABLE_PROPERTY             = "ssoptimizer.disable.resourceindex";
    /** 预读总预算（MB）：{@code -Dssoptimizer.spec.prefetch.mb}，默认 512，0 表示关闭预读。 */
    public static final  String PREFETCH_BUDGET_MB_PROPERTY  = "ssoptimizer.spec.prefetch.mb";
    private static final long   DEFAULT_PREFETCH_BUDGET_MB   = 512L;
    /** 单文件预读上限：超过 1MB 的文件不进入内存预读。 */
    private static final long   PREFETCH_FILE_MAX_BYTES      = 1L << 20;
    /** 预读范围：仅 data/ 下的文本类资源目录。 */
    private static final String PREFETCH_PREFIX              = "data/";

    private static final ConcurrentMap<String, RootSnapshot> SNAPSHOTS        = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, byte[]>       PREFETCHED       = new ConcurrentHashMap<>();
    private static final AtomicLong                          PREFETCHED_BYTES = new AtomicLong();

    private ResourceIndex() {
    }

    /** 资源索引是否启用。 */
    public static boolean isEnabled() {
        return !Boolean.getBoolean(DISABLE_PROPERTY);
    }

    /**
     * 打开根目录下相对路径对应的资源流。
     *
     * @param root DIRECTORY 资源根目录
     * @param relPath 资源相对路径（/ 或 \ 分隔均可）
     * @return 资源流；文件不存在或是目录时返回 null（与原版 openResource 语义一致）
     * @throws IOException 打开已索引文件失败时抛出
     */
    public static InputStream open(final File root, final String relPath) throws IOException {
        final Entry entry = lookup(root, relPath);
        if (entry == null || entry.directory()) {
            return null;
        }

        final byte[] prefetched = PREFETCHED.get(entry.file().getAbsolutePath());
        if (prefetched != null) {
            return new ByteArrayInputStream(prefetched);
        }
        return new BufferedInputStream(new FileInputStream(entry.file()));
    }

    /**
     * 解析根目录下相对路径对应的 File。
     *
     * @return 存在的文件 File；不存在时返回 null
     */
    public static File file(final File root, final String relPath) {
        final Entry entry = lookup(root, relPath);
        return entry == null ? null : entry.file();
    }

    /**
     * 查询资源最后修改时间。
     *
     * @return 最后修改时间毫秒；不存在时返回 0（与原版语义一致）
     */
    public static long lastModified(final File root, final String relPath) {
        final Entry entry = lookup(root, relPath);
        return entry == null ? 0L : entry.lastModified();
    }

    /** 资源是否存在（文件或目录均算存在，与 File.exists 语义一致）。 */
    public static boolean exists(final File root, final String relPath) {
        return lookup(root, relPath) != null;
    }

    /**
     * 列出根目录下某个相对目录的直接子项。
     *
     * @param relDir 相对目录路径；空串表示根目录本身
     * @return 直接子项列表；目录不存在时返回空列表
     */
    public static List<Entry> children(final File root, final String relDir) {
        final RootSnapshot snapshot = snapshot(root);
        if (snapshot == null) {
            return directChildren(root, relDir);
        }
        return snapshot.children(normalize(relDir));
    }

    /**
     * 后台并行预构建多个资源根目录的快照（已构建的自动跳过）。
     */
    public static void warmupAsync(final Collection<File> roots) {
        for (final File root : roots) {
            if (root == null) {
                continue;
            }
            submitBackground("snapshot warmup " + root.getAbsolutePath(), () -> snapshot(root));
        }
    }

    /**
     * 索引条目：一个文件或目录的快照元数据。
     *
     * @param relPath 相对根目录路径（/ 分隔，无前导 /）
     * @param file 对应的磁盘文件
     * @param directory 是否目录
     * @param lastModified 最后修改时间毫秒
     * @param size 文件字节数（目录恒为 0）
     */
    public record Entry(String relPath, File file, boolean directory, long lastModified, long size) {
    }

    static void clear() {
        SNAPSHOTS.clear();
        PREFETCHED.clear();
        PREFETCHED_BYTES.set(0L);
    }

    static String normalize(final String relPath) {
        if (relPath == null || relPath.isEmpty()) {
            return "";
        }
        String normalized = relPath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        // 目录参数允许以 / 结尾（原版 File 语义对此透明），快照键不带尾斜杠
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static Entry lookup(final File root, final String relPath) {
        final RootSnapshot snapshot = snapshot(root);
        if (snapshot == null) {
            return directEntry(root, relPath);
        }
        return snapshot.lookup(normalize(relPath));
    }

    private static RootSnapshot snapshot(final File root) {
        final RootSnapshot cached = SNAPSHOTS.get(root.getAbsolutePath());
        if (cached != null) {
            return cached == RootSnapshot.UNINDEXED ? null : cached;
        }

        RootSnapshot built;
        try {
            built = build(root);
        } catch (final UncheckedIOException | SecurityException e) {
            LOGGER.error("[SSOptimizer] Failed to index resource root " + root.getAbsolutePath()
                    + ", falling back to direct file access", e);
            built = RootSnapshot.UNINDEXED;
        }
        final RootSnapshot existing = SNAPSHOTS.putIfAbsent(root.getAbsolutePath(), built);
        if (existing != null) {
            return existing == RootSnapshot.UNINDEXED ? null : existing;
        }
        if (built != RootSnapshot.UNINDEXED) {
            schedulePrefetch(built);
        }
        return built;
    }

    private static RootSnapshot build(final File root) {
        final Path rootPath = root.toPath();
        if (!Files.isDirectory(rootPath)) {
            return RootSnapshot.UNINDEXED;
        }

        final Map<String, Entry> entries = new HashMap<>(4096);
        final Map<String, List<Entry>> children = new HashMap<>(1024);
        try (Stream<Path> stream = Files.walk(rootPath)) {
            stream.forEach(path -> {
                if (path.equals(rootPath)) {
                    return;
                }
                final String rel = normalize(rootPath.relativize(path).toString());
                final boolean directory = Files.isDirectory(path);
                long lastModified = 0L;
                long size = 0L;
                try {
                    lastModified = Files.getLastModifiedTime(path).toMillis();
                    if (!directory) {
                        size = Files.size(path);
                    }
                } catch (final IOException e) {
                    LOGGER.warn("[SSOptimizer] Failed to stat " + path + ": " + e.getMessage());
                }
                final Entry entry = new Entry(rel, path.toFile(), directory, lastModified, size);
                entries.put(rel, entry);
                final int slash = rel.lastIndexOf('/');
                children.computeIfAbsent(slash < 0 ? "" : rel.substring(0, slash), ignored -> new ArrayList<>())
                        .add(entry);
            });
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }

        // 子项列表按名称排序，保证枚举结果跨运行确定（原版 listFiles 顺序依赖文件系统）。
        for (final List<Entry> childList : children.values()) {
            childList.sort(Comparator.comparing(Entry::relPath));
        }

        return new RootSnapshot(entries, children);
    }
    private static void schedulePrefetch(final RootSnapshot snapshot) {
        final long budget = Long.getLong(PREFETCH_BUDGET_MB_PROPERTY, DEFAULT_PREFETCH_BUDGET_MB) << 20;
        if (budget <= 0) {
            return;
        }

        final List<Entry> eligible = new ArrayList<>();
        for (final Entry entry : snapshot.entries()) {
            if (!entry.directory()
                    && entry.size() > 0L
                    && entry.size() <= PREFETCH_FILE_MAX_BYTES
                    && entry.relPath().startsWith(PREFETCH_PREFIX)) {
                eligible.add(entry);
            }
        }
        if (eligible.isEmpty()) {
            return;
        }

        submitBackground("prefetch " + snapshot.entries().size() + " entries", () -> {
            for (final Entry entry : eligible) {
                final long remaining = budget - PREFETCHED_BYTES.get();
                if (remaining < entry.size()) {
                    LOGGER.info("[SSOptimizer] Resource prefetch budget exhausted at "
                            + (PREFETCHED_BYTES.get() >> 20) + "MB, skipping the rest");
                    return;
                }
                try {
                    final byte[] bytes = Files.readAllBytes(entry.file().toPath());
                    PREFETCHED.putIfAbsent(entry.file().getAbsolutePath(), bytes);
                    PREFETCHED_BYTES.addAndGet(bytes.length);
                } catch (final IOException e) {
                    LOGGER.warn("[SSOptimizer] Failed to prefetch " + entry.relPath() + ": " + e.getMessage());
                }
            }
        });
    }

    /**
     * 提交后台任务到 {@link VtWorkers}：任务体内部已逐文件记 WARN，此包装兜底
     * 捕获未预期的 RuntimeException/Error 并记 ERROR 后原样重抛（Future 保留失败状态），
     * 保证后台失败不静默。
     */
    private static void submitBackground(final String description, final Runnable task) {
        VtWorkers.submit(() -> {
            try {
                task.run();
            } catch (final RuntimeException | Error e) {
                LOGGER.error("[SSOptimizer] ResourceIndex background task failed: " + description, e);
                throw e;
            }
        });
    }

    private static Entry directEntry(final File root, final String relPath) {
        final File file = new File(root, normalize(relPath));
        if (!file.exists()) {
            return null;
        }
        final boolean directory = file.isDirectory();
        return new Entry(normalize(relPath), file, directory, file.lastModified(), directory ? 0L : file.length());
    }

    private static List<Entry> directChildren(final File root, final String relDir) {
        final File dir = normalize(relDir).isEmpty() ? root : new File(root, normalize(relDir));
        final File[] files = dir.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        final List<Entry> result = new ArrayList<>(files.length);
        final String base = normalize(relDir);
        for (final File file : files) {
            final String rel = base.isEmpty() ? file.getName() : base + "/" + file.getName();
            final boolean directory = file.isDirectory();
            result.add(new Entry(rel, file, directory, file.lastModified(), directory ? 0L : file.length()));
        }
        return result;
    }

    /**
     * 单个资源根目录的全量快照。
     */
    private static final class RootSnapshot {
        private static final RootSnapshot UNINDEXED = new RootSnapshot(Map.of(), Map.of());

        private final Map<String, Entry>       entries;
        private final Map<String, Entry>       lowercaseEntries;
        private final Map<String, List<Entry>> children;

        private RootSnapshot(final Map<String, Entry> entries,
                             final Map<String, List<Entry>> children) {
            this.entries = entries;
            this.children = children;
            final Map<String, Entry> lowercase = new HashMap<>(entries.size() * 2);
            for (final Entry entry : entries.values()) {
                lowercase.putIfAbsent(entry.relPath().toLowerCase(Locale.ROOT), entry);
            }
            this.lowercaseEntries = lowercase;
        }

        private Entry lookup(final String relPath) {
            final Entry exact = entries.get(relPath);
            if (exact != null) {
                return exact;
            }
            return lowercaseEntries.get(relPath.toLowerCase(Locale.ROOT));
        }

        private List<Entry> children(final String relDir) {
            final List<Entry> result = children.get(relDir);
            return result == null ? Collections.emptyList() : result;
        }

        private Collection<Entry> entries() {
            return entries.values();
        }
    }
}
