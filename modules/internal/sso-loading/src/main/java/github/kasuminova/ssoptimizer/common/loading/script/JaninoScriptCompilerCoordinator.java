package github.kasuminova.ssoptimizer.common.loading.script;

import github.kasuminova.ssoptimizer.asm.render.RenderThreadRedirector;
import github.kasuminova.ssoptimizer.common.concurrent.VtWorkers;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import github.kasuminova.ssoptimizer.mixin.loading.JaninoDirectoryResourceFinderAccessor;
import github.kasuminova.ssoptimizer.mixin.loading.JaninoJavaSourceClassLoaderAccessor;
import github.kasuminova.ssoptimizer.mixin.loading.JaninoJavaSourceIClassLoaderAccessor;
import github.kasuminova.ssoptimizer.mixin.loading.JaninoMultiResourceFinderAccessor;
import org.apache.log4j.Logger;
import org.codehaus.janino.JavaSourceClassLoader;
import org.codehaus.janino.JavaSourceIClassLoader;
import org.codehaus.janino.util.resource.DirectoryResourceFinder;
import org.codehaus.janino.util.resource.MultiResourceFinder;
import org.codehaus.janino.util.resource.PathResourceFinder;
import org.codehaus.janino.util.resource.Resource;
import org.codehaus.janino.util.resource.ResourceFinder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 缓存以各顶层类自身源码 SHA-256 校验；批量编译结果不得共用请求类哈希，渲染分离模式使用独立缓存目录。
 */
public final class JaninoScriptCompilerCoordinator {
    public static final String CACHE_DIR_PROPERTY       = "ssoptimizer.scriptcache.dir";
    public static final String DISABLE_CACHE_PROPERTY   = "ssoptimizer.disable.scriptcache";
    public static final String DISABLE_PREWARM_PROPERTY = "ssoptimizer.disable.scriptprewarm";
    public static final String PARALLELISM_PROPERTY     = "ssoptimizer.scriptcompile.parallelism";
    public static final String DEBUG_PROPERTY           = "ssoptimizer.debug.scriptcache";

    private static final Logger LOGGER = Logger.getLogger(JaninoScriptCompilerCoordinator.class);

    private static final int HASH_BYTES = 32;

    private static final Map<JavaSourceClassLoader, WarmupState> WARMUP_STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ConcurrentHashMap<String, byte[]> IN_MEMORY_CACHE = new ConcurrentHashMap<>();
    private static volatile JaninoAccess janinoAccess = new MixinJaninoAccess();

    private JaninoScriptCompilerCoordinator() {
    }

    public static Map<String, byte[]> generateBytecodes(final JavaSourceClassLoader loader,
                                                        final String className) throws ClassNotFoundException {
        final Map<String, byte[]> cached = tryLoadCachedBytecodes(loader, className);
        if (cached != null) {
            return cached;
        }

        final Map<String, byte[]> generated = invokeOriginalGenerate(loader, className);
        // 缓存前必须完成 GL owner 重定向，确保预热与按需编译产物一致。
        final Map<String, byte[]> redirected = RenderThreadRedirector.redirectAll(generated);
        cacheGeneratedBytecodes(loader, className, redirected);
        return redirected;
    }

    public static Map<String, byte[]> tryLoadCachedBytecodes(final JavaSourceClassLoader loader,
                                                             final String className) {
        if (loader == null || className == null || className.isBlank() || Boolean.getBoolean(DISABLE_CACHE_PROPERTY)) {
            return null;
        }

        final byte[] inMemory = IN_MEMORY_CACHE.get(className);
        if (inMemory != null) {
            return Map.of(className, inMemory);
        }

        final ResourceFinder sourceFinder = resolveSourceFinder(loader);
        if (sourceFinder == null) {
            debugLog(className, "sourceFinder 解析失败");
            return null;
        }

        final byte[] sourceHash = hashSource(sourceFinder, className);
        if (sourceHash == null) {
            debugLog(className, "源码不可定位/不可读");
            return null;
        }

        final byte[] cachedBytes = loadCachedClassFile(cacheFile(className), sourceHash);
        if (cachedBytes == null) {
            debugLog(className, "磁盘缓存缺失或哈希不匹配, 读取侧哈希=" + HexFormat.of().formatHex(sourceHash));
            return null;
        }
        debugLog(className, "磁盘缓存命中");
        return Map.of(className, cachedBytes);
    }

    private static void debugLog(final String className, final String verdict) {
        if (Boolean.getBoolean(DEBUG_PROPERTY)) {
            LOGGER.info("[SSOptimizer][ScriptCache] " + className + ": " + verdict);
        }
    }

    public static void cacheGeneratedBytecodes(final JavaSourceClassLoader loader,
                                               final String className,
                                               final Map<String, byte[]> bytecodes) {
        if (loader == null
                || className == null
                || className.isBlank()
                || bytecodes == null
                || bytecodes.isEmpty()
                || Boolean.getBoolean(DISABLE_CACHE_PROPERTY)) {
            return;
        }

        final ResourceFinder sourceFinder = resolveSourceFinder(loader);
        if (sourceFinder == null) {
            LOGGER.warn("[SSOptimizer] 无法解析脚本源码查找器 " + className + "，字节码仅写入内存缓存");
        }
        storeCachedBytecodes(sourceFinder, bytecodes);
    }

    public static void warmup(final JavaSourceClassLoader loader,
                              final String requestedClassName) {
        if (loader == null || Boolean.getBoolean(DISABLE_PREWARM_PROPERTY)) {
            return;
        }

        final List<File> sourceRoots = resolveSourceRoots(loader);
        if (sourceRoots.isEmpty()) {
            return;
        }

        final WarmupState state = warmupState(loader);
        if (!state.started().compareAndSet(false, true)) {
            return;
        }

        final List<String> discoveredClasses = discoverClassNames(sourceRoots);
        if (discoveredClasses.isEmpty()) {
            return;
        }

        discoveredClasses.sort(Comparator.comparingInt(className -> {
            if (Objects.equals(className, requestedClassName)) {
                return -1;
            }
            return 0;
        }));

        final int parallelism = configuredParallelism();
        final Path cacheDirectory = cacheDirectory();
        final File[] sourcePath = sourceRoots.toArray(File[]::new);
        final ClassLoader parentLoader = loader.getParent();
        final Semaphore gate = new Semaphore(parallelism);

        state.setFuture(CompletableFuture.allOf(discoveredClasses.stream()
                .map(className -> CompletableFuture.runAsync(
                        () -> warmupClassGated(gate, parentLoader, sourcePath, cacheDirectory, className),
                        VtWorkers::submit
                ))
                .toArray(CompletableFuture[]::new))
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        LOGGER.debug("[SSOptimizer] Janino script cache warmup finished with suppressed failures", throwable);
                    }
                }));

        LOGGER.info("[SSOptimizer] Janino script cache warmup scheduled: classes="
                + discoveredClasses.size()
                + " parallelism=" + parallelism
                + " cacheDir=" + cacheDirectory);
    }

    static boolean awaitWarmupForTests(final JavaSourceClassLoader loader,
                                       final Duration timeout) throws Exception {
        final WarmupState state = WARMUP_STATES.get(loader);
        if (state == null || state.future() == null) {
            return false;
        }

        state.future().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return true;
    }

    static void clearWarmupStateForTests() {
        synchronized (WARMUP_STATES) {
            WARMUP_STATES.clear();
        }
        IN_MEMORY_CACHE.clear();
    }

    private static void warmupClassGated(final Semaphore gate,
                                         final ClassLoader parentLoader,
                                         final File[] sourcePath,
                                         final Path cacheDirectory,
                                         final String className) {
        try {
            gate.acquire();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
        try {
            warmupClass(parentLoader, sourcePath, cacheDirectory, className);
        } finally {
            gate.release();
        }
    }

    private static void warmupClass(final ClassLoader parentLoader,
                                    final File[] sourcePath,
                                    final Path cacheDirectory,
                                    final String className) {
        try {
            final WarmupJavaSourceClassLoader worker = new WarmupJavaSourceClassLoader(
                    parentLoader,
                    sourcePath
            );
            generateBytecodes(worker, className);
        } catch (ClassNotFoundException e) {
            LOGGER.debug("[SSOptimizer] Janino warmup skipped script " + className + ": " + e.getMessage());
        }
    }

    private static byte[] loadCachedClassFile(final Path cacheFile,
                                              final byte[] expectedHash) {
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }

        try {
            final byte[] stored = Files.readAllBytes(cacheFile);
            if (stored.length <= HASH_BYTES) {
                return null;
            }
            if (!Arrays.equals(stored, 0, HASH_BYTES, expectedHash, 0, HASH_BYTES)) {
                return null;
            }
            return Arrays.copyOfRange(stored, HASH_BYTES, stored.length);
        } catch (IOException e) {
            LOGGER.debug("[SSOptimizer] 读取脚本缓存失败: " + cacheFile, e);
            return null;
        }
    }

    private static void storeCachedBytecodes(final ResourceFinder sourceFinder,
                                             final Map<String, byte[]> bytecodes) {
        if (bytecodes == null || bytecodes.isEmpty()) {
            return;
        }

        final Map<String, byte[]> hashByTopLevel = new HashMap<>();
        int stored = 0;
        for (Map.Entry<String, byte[]> entry : bytecodes.entrySet()) {
            final String className = entry.getKey();
            final byte[] bytes = entry.getValue();
            if (className == null || className.isBlank() || bytes == null || bytes.length == 0) {
                continue;
            }

            IN_MEMORY_CACHE.put(className, bytes);

            if (sourceFinder == null) {
                continue;
            }

            final String topLevel = topLevelClassName(className);
            if (hashByTopLevel.containsKey(topLevel)) {
                // null 哈希也已缓存，无需重复读取源码。
            } else {
                hashByTopLevel.put(topLevel, hashSource(sourceFinder, topLevel));
            }
            final byte[] sourceHash = hashByTopLevel.get(topLevel);
            if (sourceHash == null) {
                continue;
            }

            final Path cacheFile = cacheFile(className);
            try {
                Files.createDirectories(cacheFile.getParent());
                final byte[] storedBytes = Arrays.copyOf(sourceHash, HASH_BYTES + bytes.length);
                System.arraycopy(bytes, 0, storedBytes, HASH_BYTES, bytes.length);
                Files.write(cacheFile,
                        storedBytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                stored++;
            } catch (IOException e) {
                LOGGER.debug("[SSOptimizer] 写入脚本缓存失败: " + cacheFile, e);
            }
        }
        debugLog("batch", "写入磁盘缓存 " + stored + "/" + bytecodes.size() + " 个类");
    }

    private static String topLevelClassName(final String className) {
        final int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }

    private static ResourceFinder resolveSourceFinder(final JavaSourceClassLoader loader) {
        if (loader == null) {
            return null;
        }
        return janinoAccess.sourceFinder(loader);
    }

    private static byte[] hashSource(final ResourceFinder sourceFinder,
                                     final String className) {
        for (final String candidate : sourceFileCandidates(className)) {
            final Resource resource;
            try {
                resource = sourceFinder.findResource(candidate.replace('.', '/') + ".java");
            } catch (RuntimeException e) {
                LOGGER.debug("[SSOptimizer] 查找脚本源码失败: " + candidate, e);
                return null;
            }
            if (resource == null) {
                continue;
            }

            try (InputStream stream = resource.open()) {
                if (stream == null) {
                    continue;
                }
                return MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes());
            } catch (IOException | NoSuchAlgorithmException e) {
                LOGGER.debug("[SSOptimizer] 读取脚本源码失败: " + candidate, e);
                return null;
            }
        }
        return null;
    }

    private static Map<String, byte[]> invokeOriginalGenerate(final JavaSourceClassLoader loader,
                                                              final String className) throws ClassNotFoundException {
        if (loader == null) {
            return null;
        }
        return janinoAccess.generateBytecodes(loader, className);
    }

    private static List<File> resolveSourceRoots(final JavaSourceClassLoader loader) {
        if (loader == null) {
            return List.of();
        }
        return janinoAccess.sourceRoots(loader);
    }

    private static void collectSourceRoots(final ResourceFinder sourceFinder,
                                           final Set<File> roots) {
        if (sourceFinder == null) {
            return;
        }
        if (sourceFinder instanceof DirectoryResourceFinder directoryFinder) {
            final File directory = ((JaninoDirectoryResourceFinderAccessor) directoryFinder).ssoptimizer$getDirectory();
            if (directory != null) {
                roots.add(directory.getAbsoluteFile());
            }
            return;
        }
        if (sourceFinder instanceof MultiResourceFinder multiFinder) {
            final Collection<ResourceFinder> resourceFinders =
                    ((JaninoMultiResourceFinderAccessor) multiFinder).ssoptimizer$getResourceFinders();
            if (resourceFinders == null) {
                return;
            }
            for (ResourceFinder nestedFinder : resourceFinders) {
                collectSourceRoots(nestedFinder, roots);
            }
        }
    }

    private static List<String> discoverClassNames(final List<File> sourceRoots) {
        final LinkedHashSet<String> classNames = new LinkedHashSet<>();
        for (File root : sourceRoots) {
            if (root == null) {
                continue;
            }
            final Path rootPath = root.toPath().toAbsolutePath().normalize();
            if (!Files.isDirectory(rootPath)) {
                continue;
            }

            try (Stream<Path> paths = Files.walk(rootPath)) {
                paths.filter(Files::isRegularFile)
                     .filter(path -> path.getFileName().toString().endsWith(".java"))
                     .map(path -> rootPath.relativize(path).toString())
                     .map(relativePath -> relativePath.substring(0, relativePath.length() - ".java".length()))
                     .map(relativePath -> relativePath.replace(File.separatorChar, '.').replace('/', '.'))
                     .filter(className -> !className.isBlank())
                     .forEach(classNames::add);
            } catch (IOException e) {
                LOGGER.debug("[SSOptimizer] 遍历脚本源目录失败: " + rootPath + ": " + e.getMessage(), e);
            }
        }

        return new ArrayList<>(classNames);
    }

    private static List<String> sourceFileCandidates(final String className) {
        final List<String> candidates = new ArrayList<>();
        String current = className;
        while (current != null && !current.isBlank()) {
            candidates.add(current);
            final int nestedSeparator = current.lastIndexOf('$');
            current = nestedSeparator < 0 ? null : current.substring(0, nestedSeparator);
        }
        return candidates;
    }

    private static Path cacheDirectory() {
        final String configured = System.getProperty(CACHE_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }

        final Path modsDir = Path.of(System.getProperty("com.fs.starfarer.settings.paths.mods", "./mods"));
        final String versionDir = RenderThreadMode.isEnabled() ? "v2-rt" : "v2";
        return modsDir.resolve("ssoptimizer")
                      .resolve("cache")
                      .resolve("scripts")
                      .resolve("janino")
                      .resolve(versionDir)
                      .toAbsolutePath()
                      .normalize();
    }

    private static Path cacheFile(final String className) {
        return cacheDirectory().resolve(className.replace('.', File.separatorChar) + ".class");
    }

    private static int configuredParallelism() {
        final int configured = Integer.getInteger(PARALLELISM_PROPERTY,
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        return Math.max(1, configured);
    }

    private static WarmupState warmupState(final JavaSourceClassLoader loader) {
        synchronized (WARMUP_STATES) {
            return WARMUP_STATES.computeIfAbsent(loader, ignored -> new WarmupState(new AtomicBoolean(false), null));
        }
    }

    interface JaninoAccess {
        ResourceFinder sourceFinder(JavaSourceClassLoader loader);

        List<File> sourceRoots(JavaSourceClassLoader loader);

        Map<String, byte[]> generateBytecodes(JavaSourceClassLoader loader, String className) throws ClassNotFoundException;
    }

    static void installJaninoAccessForTests(final JaninoAccess access) {
        if (access == null) {
            throw new IllegalArgumentException("access must not be null");
        }
        janinoAccess = access;
    }

    static void resetJaninoAccessForTests() {
        janinoAccess = new MixinJaninoAccess();
    }

    private static final class MixinJaninoAccess implements JaninoAccess {
        @Override
        public ResourceFinder sourceFinder(final JavaSourceClassLoader loader) {
            final JavaSourceIClassLoader iClassLoader =
                    ((JaninoJavaSourceClassLoaderAccessor) loader).ssoptimizer$getIClassLoader();
            if (iClassLoader == null) {
                return null;
            }
            return ((JaninoJavaSourceIClassLoaderAccessor) iClassLoader).ssoptimizer$getSourceFinder();
        }

        @Override
        public List<File> sourceRoots(final JavaSourceClassLoader loader) {
            final ResourceFinder sourceFinder = sourceFinder(loader);
            if (sourceFinder == null) {
                return List.of();
            }

            final LinkedHashSet<File> roots = new LinkedHashSet<>();
            collectSourceRoots(sourceFinder, roots);
            return new ArrayList<>(roots);
        }

        @Override
        public Map<String, byte[]> generateBytecodes(final JavaSourceClassLoader loader,
                                                     final String className) throws ClassNotFoundException {
            return ((JaninoJavaSourceClassLoaderAccessor) loader).ssoptimizer$generateBytecodes(className);
        }
    }

    private static final class WarmupState {
        private final AtomicBoolean started;
        private volatile CompletableFuture<Void> future;

        private WarmupState(final AtomicBoolean started,
                            final CompletableFuture<Void> future) {
            this.started = started;
            this.future = future;
        }

        private AtomicBoolean started() {
            return started;
        }

        private CompletableFuture<Void> future() {
            return future;
        }

        private void setFuture(final CompletableFuture<Void> future) {
            this.future = future;
        }
    }

    static class WarmupJavaSourceClassLoader extends JavaSourceClassLoader {
        private final ResourceFinder sourceFinder;
        private final File[] sourcePath;

        WarmupJavaSourceClassLoader(final ClassLoader parentLoader,
                                    final File[] sourcePath) {
            this(parentLoader, new PathResourceFinder(sourcePath), sourcePath);
        }

        WarmupJavaSourceClassLoader(final ClassLoader parentLoader,
                                    final ResourceFinder sourceFinder) {
            this(parentLoader, sourceFinder, new File[0]);
        }

        private WarmupJavaSourceClassLoader(final ClassLoader parentLoader,
                                            final ResourceFinder sourceFinder,
                                            final File[] sourcePath) {
            super(parentLoader, sourceFinder, null);
            this.sourceFinder = sourceFinder;
            this.sourcePath = sourcePath.clone();
        }

        final ResourceFinder sourceFinder() {
            return sourceFinder;
        }

        final List<File> sourceRoots() {
            return List.of(sourcePath);
        }

        Map<String, byte[]> generateOriginal(final String className) throws ClassNotFoundException {
            return super.generateBytecodes(className);
        }
    }
}