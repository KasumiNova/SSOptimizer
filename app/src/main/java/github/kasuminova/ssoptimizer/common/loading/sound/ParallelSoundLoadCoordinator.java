package github.kasuminova.ssoptimizer.common.loading.sound;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.apache.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 声音资源并行预读协调器。
 * <p>
 * 该协调器负责并行读取声音文件字节，并在声音管理器按路径创建声音对象时优先复用
 * 已预读的内存字节，避免启动阶段逐个串行打开大量声音文件。
 */
public final class ParallelSoundLoadCoordinator {
    public static final String DISABLE_PROPERTY          = "ssoptimizer.disable.parallelsoundload";
    public static final String PARALLELISM_PROPERTY      = "ssoptimizer.soundload.parallelism";
    public static final String MAX_MEMORY_BYTES_PROPERTY = "ssoptimizer.soundload.cache.maxbytes";

    static final String OBJECT_FAMILY_STREAM_METHOD   = GameMemberNames.SoundManager.LOAD_OBJECT_FAMILY_FROM_STREAM;
    static final String O00000_FAMILY_STREAM_METHOD   = GameMemberNames.SoundManager.LOAD_O00000_FAMILY_FROM_STREAM;
    static final String O_ACCENT_FAMILY_STREAM_METHOD = GameMemberNames.SoundManager.LOAD_O_ACCENT_FAMILY_FROM_STREAM;
    static final String OBJECT_PATH_ORIGINAL   = "ssoptimizer$loadPathObjectFamily";
    static final String O00000_PATH_ORIGINAL   = "ssoptimizer$loadPathO00000Family";
    static final String O_ACCENT_PATH_ORIGINAL = "ssoptimizer$loadPathOAccentFamily";

    private static final Logger LOGGER = Logger.getLogger(ParallelSoundLoadCoordinator.class);

    private static final String MODS_DIR_PROPERTY           = "com.fs.starfarer.settings.paths.mods";
    private static final String RESOURCE_MANAGER_CLASS_NAME = GameClassNames.RESOURCE_LOADER.replace('/', '.');
    private static final long   DEFAULT_MAX_MEMORY_BYTES    = 128L << 20;

    private static final Object MEMORY_CACHE_LOCK = new Object();

    private static final Map<String, CompletableFuture<byte[]>> INFLIGHT_LOADS = new ConcurrentHashMap<>();
    private static final Map<String, Path>                      DISCOVERED_FILES = new ConcurrentHashMap<>();
    private static final LinkedHashMap<String, byte[]>          COMPLETED_BYTES  = new LinkedHashMap<>(16, 0.75f, true);
    private static final AtomicBoolean                          PREWARM_STARTED   = new AtomicBoolean(false);

    private static final Method RESOURCE_MANAGER_FACTORY_METHOD     = resolveResourceManagerFactoryMethod();
    private static final Method RESOURCE_MANAGER_OPEN_STREAM_METHOD = resolveResourceManagerOpenStreamMethod();

    private static volatile ExecutorService executorService;
    private static volatile long            completedBytes;

    private ParallelSoundLoadCoordinator() {
    }

    /**
    * 通过 {@code loadObjectFamily(String)} 家族方法加载声音。
     *
     * @param manager      游戏声音管理器实例
     * @param resourcePath 声音资源路径
     * @return 已创建的声音对象
     */
    public static Object loadObjectFamily(final Object manager,
                                          final String resourcePath) {
        return load(manager, resourcePath, OBJECT_FAMILY_STREAM_METHOD, OBJECT_PATH_ORIGINAL);
    }

    /**
    * 通过 {@code loadO00000Family(String)} 家族方法加载声音。
     *
     * @param manager      游戏声音管理器实例
     * @param resourcePath 声音资源路径
     * @return 已创建的声音对象
     */
    public static Object loadO00000Family(final Object manager,
                                          final String resourcePath) {
        return load(manager, resourcePath, O00000_FAMILY_STREAM_METHOD, O00000_PATH_ORIGINAL);
    }

    /**
    * 通过 {@code loadOAccentFamily(String)} 家族方法加载声音。
     *
     * @param manager      游戏声音管理器实例
     * @param resourcePath 声音资源路径
     * @return 已创建的声音对象
     */
    public static Object loadOAccentFamily(final Object manager,
                                           final String resourcePath) {
        return load(manager, resourcePath, O_ACCENT_FAMILY_STREAM_METHOD, O_ACCENT_PATH_ORIGINAL);
    }

    static void clearForTests() {
        PREWARM_STARTED.set(false);
        INFLIGHT_LOADS.clear();
        DISCOVERED_FILES.clear();
        synchronized (MEMORY_CACHE_LOCK) {
            COMPLETED_BYTES.clear();
            completedBytes = 0L;
        }
        final ExecutorService existing = executorService;
        executorService = null;
        if (existing != null) {
            existing.shutdownNow();
        }
    }

    static List<String> discoverSoundResourcesForTests(final Path gameRoot,
                                                       final Path modsDir) {
        DISCOVERED_FILES.clear();
        return discoverSoundResources(gameRoot, modsDir);
    }

    private static Object load(final Object manager,
                               final String resourcePath,
                               final String familyMethodName,
                               final String originalPathMethodName) {
        if (manager == null || resourcePath == null || resourcePath.isBlank() || Boolean.getBoolean(DISABLE_PROPERTY)) {
            return invokeOriginalPathMethod(manager, originalPathMethodName, resourcePath);
        }

        ensurePrewarmStarted();

        final byte[] bytes = awaitOrLoadBytes(normalizeResourcePath(resourcePath));
        if (bytes != null) {
            final Object loaded = invokeStringAndStreamMethod(manager, familyMethodName, resourcePath, bytes);
            if (loaded != null) {
                return loaded;
            }
        }

        return invokeOriginalPathMethod(manager, originalPathMethodName, resourcePath);
    }

    private static byte[] awaitOrLoadBytes(final String resourcePath) {
        final byte[] completed = lookupCompletedBytes(resourcePath);
        if (completed != null) {
            return completed;
        }

        final CompletableFuture<byte[]> future = INFLIGHT_LOADS.computeIfAbsent(resourcePath,
                ignored -> CompletableFuture.supplyAsync(() -> {
                    try {
                        final byte[] loaded = loadBytes(resourcePath);
                        if (loaded != null) {
                            rememberCompletedBytes(resourcePath, loaded);
                        }
                        return loaded;
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, executor()));

        try {
            final byte[] loaded = future.join();
            INFLIGHT_LOADS.remove(resourcePath, future);
            return loaded;
        } catch (CompletionException ignored) {
            INFLIGHT_LOADS.remove(resourcePath, future);
            return null;
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
                    ignored -> CompletableFuture.supplyAsync(() -> {
                        try {
                            final byte[] loaded = loadBytes(resource);
                            if (loaded != null) {
                                rememberCompletedBytes(resource, loaded);
                            }
                            return loaded;
                        } catch (IOException e) {
                            return null;
                        }
                    }, executor()));
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
            } catch (IOException ignored) {
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
        } catch (IOException ignored) {
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

    private static Object invokeStringAndStreamMethod(final Object manager,
                                                      final String methodName,
                                                      final String resourcePath,
                                                      final byte[] bytes) {
        try {
            final Method method = manager.getClass().getDeclaredMethod(methodName, String.class, InputStream.class);
            method.setAccessible(true);
            return method.invoke(manager, resourcePath, new ByteArrayInputStream(bytes));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        } catch (InvocationTargetException e) {
            return null;
        }
    }

    private static Object invokeOriginalPathMethod(final Object manager,
                                                   final String methodName,
                                                   final String resourcePath) {
        if (manager == null) {
            return null;
        }

        try {
            final Method method = manager.getClass().getDeclaredMethod(methodName, String.class);
            method.setAccessible(true);
            return method.invoke(manager, resourcePath);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        } catch (InvocationTargetException e) {
            return throwUnchecked(e.getCause());
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

    private static ExecutorService executor() {
        ExecutorService current = executorService;
        if (current != null) {
            return current;
        }

        synchronized (ParallelSoundLoadCoordinator.class) {
            if (executorService != null) {
                return executorService;
            }
            executorService = Executors.newFixedThreadPool(configuredParallelism(), soundThreadFactory());
            return executorService;
        }
    }

    private static int configuredParallelism() {
        final int configured = Integer.getInteger(PARALLELISM_PROPERTY,
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        return Math.max(1, configured);
    }

    private static ThreadFactory soundThreadFactory() {
        final AtomicInteger threadCounter = new AtomicInteger(1);
        return runnable -> {
            final Thread thread = new Thread(runnable, "SSOptimizer-SoundLoad-" + threadCounter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
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
        final Method factoryMethod = RESOURCE_MANAGER_FACTORY_METHOD;
        final Method openStreamMethod = RESOURCE_MANAGER_OPEN_STREAM_METHOD;
        if (factoryMethod == null || openStreamMethod == null || resourcePath == null || resourcePath.isBlank()) {
            return null;
        }

        try {
            final Object manager = factoryMethod.invoke(null);
            if (manager == null) {
                return null;
            }
            return (InputStream) openStreamMethod.invoke(manager, resourcePath);
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            return null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static Method resolveResourceManagerFactoryMethod() {
        try {
            final Class<?> resourceManagerClass = Class.forName(RESOURCE_MANAGER_CLASS_NAME, false, ParallelSoundLoadCoordinator.class.getClassLoader());
            for (Method candidate : resourceManagerClass.getDeclaredMethods()) {
                if (candidate.getReturnType() == resourceManagerClass
                        && candidate.getParameterCount() == 0
                        && java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) {
                    candidate.setAccessible(true);
                    return candidate;
                }
            }
            return null;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Method resolveResourceManagerOpenStreamMethod() {
        try {
            final Class<?> resourceManagerClass = Class.forName(RESOURCE_MANAGER_CLASS_NAME, false, ParallelSoundLoadCoordinator.class.getClassLoader());
            final Method method = resourceManagerClass.getDeclaredMethod("getStream", String.class);
            method.setAccessible(true);
            return method;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T throwUnchecked(final Throwable throwable) throws E {
        throw (E) throwable;
    }

}