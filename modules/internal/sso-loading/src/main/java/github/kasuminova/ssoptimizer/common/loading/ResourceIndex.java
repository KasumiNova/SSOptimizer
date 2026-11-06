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
 * 资源根快照与预读内容仅在 clear 后失效；索引构建失败的根目录回退直接文件访问。
 */
public final class ResourceIndex {
    private static final Logger LOGGER = Logger.getLogger(ResourceIndex.class);

    public static final  String DISABLE_PROPERTY             = "ssoptimizer.disable.resourceindex";
    public static final  String PREFETCH_BUDGET_MB_PROPERTY  = "ssoptimizer.spec.prefetch.mb";
    private static final long   DEFAULT_PREFETCH_BUDGET_MB   = 512L;
    private static final long   PREFETCH_FILE_MAX_BYTES      = 1L << 20;
    private static final String PREFETCH_PREFIX              = "data/";

    private static final ConcurrentMap<String, RootSnapshot> SNAPSHOTS        = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, byte[]>       PREFETCHED       = new ConcurrentHashMap<>();
    private static final AtomicLong                          PREFETCHED_BYTES = new AtomicLong();

    private ResourceIndex() {
    }

    public static boolean isEnabled() {
        return !Boolean.getBoolean(DISABLE_PROPERTY);
    }

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

    public static File file(final File root, final String relPath) {
        final Entry entry = lookup(root, relPath);
        return entry == null ? null : entry.file();
    }

    public static long lastModified(final File root, final String relPath) {
        final Entry entry = lookup(root, relPath);
        return entry == null ? 0L : entry.lastModified();
    }

    public static boolean exists(final File root, final String relPath) {
        return lookup(root, relPath) != null;
    }

    public static List<Entry> children(final File root, final String relDir) {
        final RootSnapshot snapshot = snapshot(root);
        if (snapshot == null) {
            return directChildren(root, relDir);
        }
        return snapshot.children(normalize(relDir));
    }

    public static void warmupAsync(final Collection<File> roots) {
        for (final File root : roots) {
            if (root == null) {
                continue;
            }
            submitBackground("snapshot warmup " + root.getAbsolutePath(), () -> snapshot(root));
        }
    }

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
        // 快照键需与 File 的词法归一一致；保留逃逸根目录的 .. 段使查询必然未命中。
        final String[] segments = relPath.replace('\\', '/').split("/");
        final List<String> stack = new ArrayList<>(segments.length);
        for (final String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                final int last = stack.size() - 1;
                if (last >= 0 && !"..".equals(stack.get(last))) {
                    stack.remove(last);
                } else {
                    stack.add(segment);
                }
                continue;
            }
            stack.add(segment);
        }
        return String.join("/", stack);
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
