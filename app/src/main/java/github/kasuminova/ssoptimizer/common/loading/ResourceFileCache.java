package github.kasuminova.ssoptimizer.common.loading;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight startup-time virtual filesystem snapshot for the base-game
 * resource loader. It memoizes file metadata and directory children so the
 * engine's repeated "does this path exist?" and "list matching files" probes
 * collapse into in-memory lookups after the first hit.
 */
public final class ResourceFileCache {
    private static final Map<String, FileMetadata>      FILE_CACHE      = new ConcurrentHashMap<>();
    private static final Map<String, DirectorySnapshot> DIRECTORY_CACHE = new ConcurrentHashMap<>();

    private ResourceFileCache() {
    }

    public static boolean exists(File file) {
        return metadata(file).exists();
    }

    public static boolean isDirectory(File file) {
        return metadata(file).directory();
    }

    public static long lastModified(File file) {
        return metadata(file).lastModified();
    }

    public static File[] listFiles(File directory) {
        if (directory == null || isDisabled()) {
            return directList(directory);
        }

        return directorySnapshot(directory).children().clone();
    }

    public static File[] listFiles(File directory, FilenameFilter filter) {
        File[] children = listFiles(directory);
        if (filter == null || children.length == 0) {
            return children;
        }

        return Arrays.stream(children)
                     .filter(child -> filter.accept(directory, child.getName()))
                     .toArray(File[]::new);
    }

    static void clear() {
        FILE_CACHE.clear();
        DIRECTORY_CACHE.clear();
    }

    private static FileMetadata metadata(File file) {
        if (file == null || isDisabled()) {
            return probe(file);
        }

        String path = file.getAbsolutePath();
        FileMetadata cached = FILE_CACHE.get(path);
        if (cached != null) {
            return cached;
        }

        FileMetadata inferred = metadataFromParentSnapshot(file);
        if (inferred != null) {
            FileMetadata existing = FILE_CACHE.putIfAbsent(path, inferred);
            return existing != null ? existing : inferred;
        }

        return FILE_CACHE.computeIfAbsent(path, ignored -> probe(file));
    }

    private static boolean isDisabled() {
        return Boolean.getBoolean("ssoptimizer.disable.resourcefilecache");
    }

    private static FileMetadata probe(File file) {
        if (file == null) {
            return FileMetadata.MISSING;
        }

        boolean exists = file.exists();
        boolean directory = exists && file.isDirectory();
        long lastModified = exists ? file.lastModified() : 0L;
        return new FileMetadata(exists, directory, lastModified);
    }

    private static FileMetadata metadataFromParentSnapshot(File file) {
        File parent = file.getParentFile();
        if (parent == null) {
            return null;
        }

        DirectorySnapshot snapshot = directorySnapshot(parent);
        if (!snapshot.exists() || !snapshot.directory()) {
            return FileMetadata.MISSING;
        }

        return snapshot.child(file.getName());
    }

    private static DirectorySnapshot directorySnapshot(File directory) {
        if (directory == null || isDisabled()) {
            return scanDirectory(directory);
        }

        return DIRECTORY_CACHE.computeIfAbsent(directory.getAbsolutePath(), ignored -> scanDirectory(directory));
    }

    private static DirectorySnapshot scanDirectory(File directory) {
        if (directory == null) {
            return DirectorySnapshot.MISSING;
        }

        FileMetadata directoryMetadata = probe(directory);
        FILE_CACHE.putIfAbsent(directory.getAbsolutePath(), directoryMetadata);
        if (!directoryMetadata.exists() || !directoryMetadata.directory()) {
            return new DirectorySnapshot(directoryMetadata.exists(), directoryMetadata.directory(), new File[0], Map.of());
        }

        File[] files = directList(directory);
        Map<String, FileMetadata> children = new HashMap<>(Math.max(4, files.length * 2));
        for (File child : files) {
            FileMetadata childMetadata = new FileMetadata(true, child.isDirectory(), child.lastModified());
            children.put(child.getName(), childMetadata);
            FILE_CACHE.putIfAbsent(child.getAbsolutePath(), childMetadata);
        }

        return new DirectorySnapshot(true, true, files, Map.copyOf(children));
    }

    private static File[] directList(File directory) {
        if (directory == null) {
            return new File[0];
        }

        File[] files = directory.listFiles();
        return files != null ? files : new File[0];
    }

    private record FileMetadata(boolean exists, boolean directory, long lastModified) {
        private static final FileMetadata MISSING = new FileMetadata(false, false, 0L);
    }

    private record DirectorySnapshot(boolean exists,
                                     boolean directory,
                                     File[] children,
                                     Map<String, FileMetadata> childrenMetadata) {
        private static final DirectorySnapshot MISSING = new DirectorySnapshot(false, false, new File[0], Map.of());

        private FileMetadata child(String name) {
            return childrenMetadata.getOrDefault(name, FileMetadata.MISSING);
        }
    }
}