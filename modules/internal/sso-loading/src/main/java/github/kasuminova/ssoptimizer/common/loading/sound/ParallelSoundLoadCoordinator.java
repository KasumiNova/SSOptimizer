package github.kasuminova.ssoptimizer.common.loading.sound;

import com.fs.util.ResourceLoader;
import github.kasuminova.ssoptimizer.common.concurrent.VtWorkers;
import github.kasuminova.ssoptimizer.mixin.loading.SoundManagerAccessor;
import org.apache.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 完成的 future 必须立即从 INFLIGHT_LOADS 移除，避免已加载字节被重复持有。
 */
public final class ParallelSoundLoadCoordinator {
    public static final String DISABLE_PROPERTY          = "ssoptimizer.disable.parallelsoundload";
    public static final String PARALLELISM_PROPERTY      = "ssoptimizer.soundload.parallelism";
    public static final String MAX_MEMORY_BYTES_PROPERTY = "ssoptimizer.soundload.cache.maxbytes";

    private static final Logger LOGGER = Logger.getLogger(ParallelSoundLoadCoordinator.class);

    private static final String MODS_DIR_PROPERTY        = "com.fs.starfarer.settings.paths.mods";
    private static final long   DEFAULT_MAX_MEMORY_BYTES = 128L << 20;

    private static final Object MEMORY_CACHE_LOCK = new Object();

    private static final Map<String, CompletableFuture<byte[]>> INFLIGHT_LOADS = new ConcurrentHashMap<>();
    private static final Map<String, Path>                      DISCOVERED_FILES = new ConcurrentHashMap<>();
    private static final LinkedHashMap<String, byte[]>          COMPLETED_BYTES  = new LinkedHashMap<>(16, 0.75f, true);
    private static final AtomicBoolean                          PREWARM_STARTED   = new AtomicBoolean(false);
    private static final BytesLoader                            DEFAULT_BYTES_LOADER = ParallelSoundLoadCoordinator::loadBytes;

    private static volatile Semaphore   loadGate;
    private static volatile long        completedBytes;
    private static volatile BytesLoader bytesLoader = DEFAULT_BYTES_LOADER;

    private ParallelSoundLoadCoordinator() {
    }

    public static Object loadObjectFamily(final Object manager,
                                          final String resourcePath) {
        return load(manager, resourcePath, SoundFamily.OBJECT);
    }

    public static Object loadO00000Family(final Object manager,
                                          final String resourcePath) {
        return load(manager, resourcePath, SoundFamily.O00000);
    }

    public static Object loadOAccentFamily(final Object manager,
                                           final String resourcePath) {
        return load(manager, resourcePath, SoundFamily.O_ACCENT);
    }

    static void clearForTests() {
        PREWARM_STARTED.set(false);
        INFLIGHT_LOADS.clear();
        DISCOVERED_FILES.clear();
        synchronized (MEMORY_CACHE_LOCK) {
            COMPLETED_BYTES.clear();
            completedBytes = 0L;
        }
        loadGate = null;
        bytesLoader = DEFAULT_BYTES_LOADER;
    }

    static int inflightLoadCountForTests() {
        return INFLIGHT_LOADS.size();
    }

    static void installBytesLoaderForTests(final BytesLoader loader) {
        bytesLoader = loader;
    }

    static List<String> discoverSoundResourcesForTests(final Path gameRoot,
                                                       final Path modsDir) {
        DISCOVERED_FILES.clear();
        return discoverSoundResources(gameRoot, modsDir);
    }

    private static Object load(final Object manager,
                               final String resourcePath,
                               final SoundFamily family) {
        if (manager == null || resourcePath == null || resourcePath.isBlank() || Boolean.getBoolean(DISABLE_PROPERTY)) {
            return null;
        }

        ensurePrewarmStarted();

        final String normalizedPath = normalizeResourcePath(resourcePath);
        final ByteLoadResult byteResult = awaitOrLoadBytes(normalizedPath);
        if (byteResult.failure() != null) {
            LOGGER.warn("[SSOptimizer] Parallel sound preload failed for " + normalizedPath
                    + ", using original path loader", byteResult.failure());
            return null;
        }
        if (byteResult.bytes() == null) {
            return null;
        }

        try {
            return family.loadFromStream((SoundManagerAccessor) manager,
                    resourcePath, new ByteArrayInputStream(byteResult.bytes()));
        } catch (IOException e) {
            LOGGER.warn("[SSOptimizer] Sound stream decode failed for " + resourcePath
                    + ", using original path loader", e);
            return null;
        }
    }

    private static ByteLoadResult awaitOrLoadBytes(final String resourcePath) {
        final byte[] completed = lookupCompletedBytes(resourcePath);
        if (completed != null) {
            return new ByteLoadResult(completed, null);
        }

        final CompletableFuture<byte[]> future = INFLIGHT_LOADS.computeIfAbsent(resourcePath,
                ignored -> CompletableFuture.supplyAsync(() -> {
                    try {
                        final byte[] loaded = loadBytesGated(resourcePath);
                        if (loaded != null) {
                            rememberCompletedBytes(resourcePath, loaded);
                        }
                        return loaded;
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, VtWorkers::submit));

        try {
            return new ByteLoadResult(future.join(), null);
        } catch (CompletionException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new ByteLoadResult(null, cause);
        } finally {
            INFLIGHT_LOADS.remove(resourcePath, future);
        }
    }

    private static void ensurePrewarmStarted() {
        if (Boolean.getBoolean(DISABLE_PROPERTY) || !PREWARM_STARTED.compareAndSet(false, true)) {
            return;
        }

        final Path modsDir = modsDirectory();
        final Path gameRoot = gameRootDirectory(modsDir);
        final List<String> resources = discoverSoundResources(gameRoot, modsDir);
        if (resources.isEmpty()) {
            return;
        }

        for (String resource : resources) {
            INFLIGHT_LOADS.computeIfAbsent(resource,
                    ignored -> {
                        final CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                            try {
                                final byte[] loaded = loadBytesGated(resource);
                                if (loaded != null) {
                                    rememberCompletedBytes(resource, loaded);
                                }
                                return loaded;
                            } catch (IOException e) {
                                LOGGER.warn("[SSOptimizer] Sound preload failed for " + resource + ": "
                                        + e.getMessage(), e);
                                return null;
                            }
                        }, VtWorkers::submit);
                        future.whenComplete((loaded, error) -> {
                            INFLIGHT_LOADS.remove(resource, future);
                            if (error != null) {
                                LOGGER.warn("[SSOptimizer] Sound preload failed for " + resource, error);
                            }
                        });
                        return future;
                    });
        }

        LOGGER.info("[SSOptimizer] Parallel sound preload scheduled: resources="
                + resources.size()
                + " parallelism=" + configuredParallelism());
    }

    private static List<String> discoverSoundResources(final Path gameRoot,
                                                       final Path modsDir) {
        final LinkedHashSet<String> resources = new LinkedHashSet<>();
        scanSoundRoot(gameRoot != null ? gameRoot.resolve("sounds") : null, resources);

        if (modsDir != null && Files.isDirectory(modsDir)) {
            try (Stream<Path> modEntries = Files.list(modsDir)) {
                modEntries.filter(Files::isDirectory)
                          .forEach(modPath -> scanSoundRoot(modPath.resolve("sounds"), resources));
            } catch (IOException e) {
                LOGGER.debug("[SSOptimizer] Failed to list mods dir " + modsDir + ": " + e.getMessage(), e);
            }
        }

        return new ArrayList<>(resources);
    }

    private static void scanSoundRoot(final Path soundRoot,
                                      final Collection<String> resources) {
        if (soundRoot == null || !Files.isDirectory(soundRoot)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(soundRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(ParallelSoundLoadCoordinator::isSupportedSoundFile)
                 .forEach(path -> {
                     final String resourcePath = normalizeResourcePath("sounds/" + soundRoot.relativize(path).toString());
                     resources.add(resourcePath);
                     DISCOVERED_FILES.putIfAbsent(resourcePath, path.toAbsolutePath().normalize());
                 });
        } catch (IOException e) {
            LOGGER.debug("[SSOptimizer] Failed to scan sound root " + soundRoot + ": " + e.getMessage(), e);
        }
    }

    private static boolean isSupportedSoundFile(final Path path) {
        final String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".ogg") || fileName.endsWith(".wav");
    }

    private static byte[] loadBytes(final String resourcePath) throws IOException {
        final String normalizedPath = normalizeResourcePath(resourcePath);
        try (InputStream managed = openManagedStream(normalizedPath)) {
            if (managed != null) {
                return managed.readAllBytes();
            }
        }

        final Path discovered = DISCOVERED_FILES.get(normalizedPath);
        if (discovered != null && Files.isRegularFile(discovered)) {
            return Files.readAllBytes(discovered);
        }

        final Path directFile = Path.of(normalizedPath).toAbsolutePath().normalize();
        if (Files.isRegularFile(directFile)) {
            return Files.readAllBytes(directFile);
        }

        final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        InputStream inputStream = contextLoader != null ? contextLoader.getResourceAsStream(normalizedPath) : null;
        if (inputStream == null) {
            inputStream = ParallelSoundLoadCoordinator.class.getClassLoader().getResourceAsStream(normalizedPath);
        }
        if (inputStream == null) {
            return null;
        }

        try (InputStream input = inputStream) {
            return input.readAllBytes();
        }
    }

    private static byte[] lookupCompletedBytes(final String resourcePath) {
        synchronized (MEMORY_CACHE_LOCK) {
            return COMPLETED_BYTES.get(resourcePath);
        }
    }

    private static void rememberCompletedBytes(final String resourcePath,
                                               final byte[] bytes) {
        final long maxBytes = maximumMemoryBytes();
        if (bytes == null || bytes.length == 0 || maxBytes <= 0L) {
            return;
        }

        synchronized (MEMORY_CACHE_LOCK) {
            final byte[] previous = COMPLETED_BYTES.remove(resourcePath);
            if (previous != null) {
                completedBytes -= previous.length;
            }

            if (bytes.length > maxBytes) {
                return;
            }

            COMPLETED_BYTES.put(resourcePath, bytes);
            completedBytes += bytes.length;

            while (completedBytes > maxBytes && !COMPLETED_BYTES.isEmpty()) {
                final Map.Entry<String, byte[]> eldest = COMPLETED_BYTES.entrySet().iterator().next();
                COMPLETED_BYTES.remove(eldest.getKey());
                completedBytes -= eldest.getValue().length;
            }
        }
    }

    private static long maximumMemoryBytes() {
        return Math.max(0L, Long.getLong(MAX_MEMORY_BYTES_PROPERTY, DEFAULT_MAX_MEMORY_BYTES));
    }

    private static byte[] loadBytesGated(final String resourcePath) throws IOException {
        final Semaphore gate = loadGate();
        try {
            gate.acquire();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
        try {
            return bytesLoader.load(resourcePath);
        } finally {
            gate.release();
        }
    }

    private static Semaphore loadGate() {
        Semaphore current = loadGate;
        if (current != null) {
            return current;
        }

        synchronized (ParallelSoundLoadCoordinator.class) {
            if (loadGate == null) {
                loadGate = new Semaphore(configuredParallelism());
            }
            return loadGate;
        }
    }

    private static int configuredParallelism() {
        final int configured = Integer.getInteger(PARALLELISM_PROPERTY,
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        return Math.max(1, configured);
    }

    private static Path modsDirectory() {
        final String configured = System.getProperty(MODS_DIR_PROPERTY, "./mods");
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static Path gameRootDirectory(final Path modsDir) {
        if (modsDir == null) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        final Path parent = modsDir.getParent();
        return parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
    }

    private static String normalizeResourcePath(final String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return "";
        }
        final String normalized = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        return normalized.replace('\\', '/');
    }

    private static InputStream openManagedStream(final String resourcePath) throws IOException {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }
        try {
            return ResourceLoader.getInstance().openStream(resourcePath);
        } catch (final RuntimeException e) {
            // 原版以 RuntimeException 表示所有资源根均未命中，缺失时沿用既有回落链。
            LOGGER.debug("[SSOptimizer] 托管资源未命中: " + resourcePath + ": " + e.getMessage());
            return null;
        }
    }

    @FunctionalInterface
    interface BytesLoader {
        byte[] load(String resourcePath) throws IOException;
    }

    private enum SoundFamily {
        OBJECT {
            @Override
            Object loadFromStream(final SoundManagerAccessor manager,
                                  final String resourcePath,
                                  final InputStream inputStream) throws IOException {
                return manager.ssoptimizer$loadObjectFamilyFromStream(resourcePath, inputStream);
            }
        },
        O00000 {
            @Override
            Object loadFromStream(final SoundManagerAccessor manager,
                                  final String resourcePath,
                                  final InputStream inputStream) throws IOException {
                return manager.ssoptimizer$loadO00000FamilyFromStream(resourcePath, inputStream);
            }
        },
        O_ACCENT {
            @Override
            Object loadFromStream(final SoundManagerAccessor manager,
                                  final String resourcePath,
                                  final InputStream inputStream) throws IOException {
                return manager.ssoptimizer$loadOAccentFamilyFromStream(resourcePath, inputStream);
            }
        };

        abstract Object loadFromStream(SoundManagerAccessor manager,
                                       String resourcePath,
                                       InputStream inputStream) throws IOException;
    }

    private record ByteLoadResult(byte[] bytes, Throwable failure) {
    }
}