package github.kasuminova.ssoptimizer.common.loading.script;

import github.kasuminova.ssoptimizer.asm.render.RenderThreadRedirector;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.apache.log4j.Logger;
import org.codehaus.janino.JavaSourceClassLoader;
import org.codehaus.janino.JavaSourceIClassLoader;
import org.codehaus.janino.util.resource.DirectoryResourceFinder;
import org.codehaus.janino.util.resource.MultiResourceFinder;
import org.codehaus.janino.util.resource.Resource;
import org.codehaus.janino.util.resource.ResourceFinder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Janino 脚本编译缓存与并行预热协调器。
 * <p>
 * 该协调器负责两件事：
 * <ul>
 *     <li>把 {@link JavaSourceClassLoader} 生成的 class 字节码落盘缓存，后续优先从缓存读取；</li>
 *     <li>在首次脚本类加载时后台并行预编译同一 source path 下的其他脚本，降低后续串行卡顿。</li>
 * </ul>
 * <p>
 * 缓存有效性以脚本源码内容哈希（SHA-256）判定：读取时经 loader 上的
 * {@link ResourceFinder} 取回（可能被剥离注解后的）源码字节并计算哈希，
 * 与缓存文件头部存储的哈希比对。游戏的 {@code ScriptSourceFinder} 并非目录型
 * finder，基于目录 mtime 的校验对它永远失效（缓存只写不读），故统一采用内容哈希。
 * 缓存文件格式为 {@code [32B 源码哈希][class 字节码]}，目录版本 v2。
 */
public final class JaninoScriptCompilerCoordinator {
    public static final String CACHE_DIR_PROPERTY       = "ssoptimizer.scriptcache.dir";
    public static final String DISABLE_CACHE_PROPERTY   = "ssoptimizer.disable.scriptcache";
    public static final String DISABLE_PREWARM_PROPERTY = "ssoptimizer.disable.scriptprewarm";
    public static final String PARALLELISM_PROPERTY     = "ssoptimizer.scriptcompile.parallelism";
    public static final String DEBUG_PROPERTY           = "ssoptimizer.debug.scriptcache";
    public static final String ORIGINAL_METHOD_NAME     = "ssoptimizer$generateBytecodesOriginal";

    private static final Logger LOGGER = Logger.getLogger(JaninoScriptCompilerCoordinator.class);

    /** 缓存文件头部源码哈希长度（SHA-256）。 */
    private static final int HASH_BYTES = 32;

    private static final Field ICLASS_LOADER_FIELD    = resolveField(JavaSourceClassLoader.class, "iClassLoader");
    private static final Field SOURCE_FINDER_FIELD    = resolveField(JavaSourceIClassLoader.class, "sourceFinder");
    private static final Field RESOURCE_FINDERS_FIELD = resolveField(MultiResourceFinder.class, "resourceFinders");
    private static final Field DIRECTORY_FIELD        = resolveField(DirectoryResourceFinder.class, "directory");

    private static final Map<JavaSourceClassLoader, WarmupState> WARMUP_STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ConcurrentHashMap<String, byte[]> IN_MEMORY_CACHE = new ConcurrentHashMap<>();

    private JaninoScriptCompilerCoordinator() {
    }

    /**
     * 生成或复用指定脚本类的字节码缓存。
     *
     * @param loader    Janino 脚本类加载器
     * @param className 脚本类名
     * @return 该类对应的编译字节码集合
     * @throws ClassNotFoundException 当 Janino 无法编译或找不到类时抛出
     */
    public static Map<String, byte[]> generateBytecodes(final JavaSourceClassLoader loader,
                                                        final String className) throws ClassNotFoundException {
        final Map<String, byte[]> cached = tryLoadCachedBytecodes(loader, className);
        if (cached != null) {
            return cached;
        }

        final Map<String, byte[]> generated = invokeOriginalGenerate(loader, className);
        // 分离模式：写入缓存前先完成 GL owner 重定向（缓存目录按模式分版，见
        // cacheDirectory），保证 warmup 与主线程编译产物同样被改写
        final Map<String, byte[]> redirected = RenderThreadRedirector.redirectAll(generated);
        cacheGeneratedBytecodes(loader, className, redirected);
        return redirected;
    }

    /**
     * 尝试从磁盘缓存读取指定脚本类的字节码。
     * <p>
     * 通过 loader 上的 {@link ResourceFinder} 取回源码并计算内容哈希，
     * 与缓存文件头部的哈希比对，一致才视为命中；不依赖目录型 source root，
     * 因此对游戏的 {@code ScriptSourceFinder} 同样有效。
     *
     * @param loader    Janino 脚本类加载器
     * @param className 脚本类名
     * @return 命中缓存时返回字节码集合，否则返回 {@code null}
     */
    public static Map<String, byte[]> tryLoadCachedBytecodes(final JavaSourceClassLoader loader,
                                                             final String className) {
        if (loader == null || className == null || className.isBlank() || Boolean.getBoolean(DISABLE_CACHE_PROPERTY)) {
            return null;
        }

        // 优先从内存缓存取（warmup 线程编译后直接写入，免磁盘 round-trip）
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

    /**
     * 将 Janino 原始编译结果写入磁盘缓存。
     * <p>
     * 磁盘缓存以源码内容哈希为有效性凭据；若此时无法取回源码
     * （理论上刚编译过必然可读），记录告警并只保留内存缓存。
     *
     * @param loader     Janino 脚本类加载器
     * @param className  脚本类名
     * @param bytecodes  原始编译输出
     */
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

    /**
     * 在首次脚本类查找时并行预热脚本编译缓存。
     *
     * @param loader             Janino 脚本类加载器
     * @param requestedClassName 当前正在查找的脚本类名
     */
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
        final ThreadFactory threadFactory = warmupThreadFactory();
        final ExecutorService executor = Executors.newFixedThreadPool(parallelism, threadFactory);

        state.setFuture(CompletableFuture.allOf(discoveredClasses.stream()
                .map(className -> CompletableFuture.runAsync(
                        () -> warmupClass(parentLoader, sourcePath, cacheDirectory, className),
                        executor
                ))
                .toArray(CompletableFuture[]::new))
                .whenComplete((ignored, throwable) -> {
                    executor.shutdown();
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
        } catch (ClassNotFoundException ignored) {
        }
    }

    /**
     * 读取并校验磁盘缓存文件。
     *
     * @param cacheFile    缓存文件路径
     * @param expectedHash 当前源码的内容哈希
     * @return 哈希一致时返回 class 字节码，否则返回 {@code null}
     */
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

    /**
     * 将编译产物写入内存与磁盘缓存。
     * <p>
     * 游戏定制版 Janino 的 {@code generateBytecodes} 会累积编译所有已排队的
     * 编译单元并一次性返回批量结果，因此 {@code bytecodes} 中可能包含与本次
     * 请求类无关的其他脚本类。每个条目必须以其<strong>自身顶层类</strong>的
     * 源码内容哈希为有效性凭据，否则批量写入会用错误哈希污染全部缓存文件，
     * 导致后续启动永远无法命中。
     */
    private static void storeCachedBytecodes(final ResourceFinder sourceFinder,
                                             final Map<String, byte[]> bytecodes) {
        if (bytecodes == null || bytecodes.isEmpty()) {
            return;
        }

        // 同一顶层类的嵌套类共享源文件，哈希只算一次；null 哈希表示源码不可读，跳过磁盘写入
        final Map<String, byte[]> hashByTopLevel = new HashMap<>();
        int stored = 0;
        for (Map.Entry<String, byte[]> entry : bytecodes.entrySet()) {
            final String className = entry.getKey();
            final byte[] bytes = entry.getValue();
            if (className == null || className.isBlank() || bytes == null || bytes.length == 0) {
                continue;
            }

            // 同时写入内存缓存，使主线程可直接取用而无需再走磁盘
            IN_MEMORY_CACHE.put(className, bytes);

            if (sourceFinder == null) {
                continue;
            }

            final String topLevel = topLevelClassName(className);
            if (hashByTopLevel.containsKey(topLevel)) {
                // 已判定过：null 表示源码不可读
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

    /** 取类名的顶层类（嵌套类 {@code Outer$Inner} 归一到 {@code Outer}）。 */
    private static String topLevelClassName(final String className) {
        final int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }

    /**
     * 从 loader 的 {@code iClassLoader.sourceFinder} 解析出 {@link ResourceFinder}。
     *
     * @return 解析失败时返回 {@code null}
     */
    private static ResourceFinder resolveSourceFinder(final JavaSourceClassLoader loader) {
        if (loader == null || ICLASS_LOADER_FIELD == null || SOURCE_FINDER_FIELD == null) {
            return null;
        }

        try {
            final Object iClassLoader = ICLASS_LOADER_FIELD.get(loader);
            if (iClassLoader == null) {
                return null;
            }
            final Object sourceFinder = SOURCE_FINDER_FIELD.get(iClassLoader);
            return sourceFinder instanceof ResourceFinder resourceFinder ? resourceFinder : null;
        } catch (IllegalAccessException e) {
            LOGGER.debug("[SSOptimizer] 无法访问 Janino sourceFinder", e);
            return null;
        }
    }

    /**
     * 通过 {@link ResourceFinder} 取回脚本源码并计算 SHA-256。
     * <p>
     * 对嵌套类名（{@code Outer$Inner}）逐级回退到顶层类名查找源文件，
     * 与 Janino 的资源命名约定（{@code pkg/Outer.java}）一致。
     *
     * @return 源码内容哈希；源码不可定位或不可读时返回 {@code null}
     */
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

        try {
            final Method method = resolveOriginalGenerateMethod(loader.getClass());
            return castBytecodeMap(method.invoke(loader, className));
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof ClassNotFoundException classNotFoundException) {
                throw classNotFoundException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ClassNotFoundException(className, cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to invoke original Janino bytecode generator", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, byte[]> castBytecodeMap(final Object value) {
        return (Map<String, byte[]>) value;
    }

    private static Method resolveOriginalGenerateMethod(final Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                final Method method = current.getDeclaredMethod(ORIGINAL_METHOD_NAME, String.class);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new IllegalStateException("Original Janino bytecode generator method is unavailable");
    }

    private static List<File> resolveSourceRoots(final JavaSourceClassLoader loader) {
        final ResourceFinder sourceFinder = resolveSourceFinder(loader);
        if (sourceFinder == null) {
            return List.of();
        }

        try {
            final LinkedHashSet<File> roots = new LinkedHashSet<>();
            collectSourceRoots(sourceFinder, roots);
            return new ArrayList<>(roots);
        } catch (IllegalAccessException e) {
            LOGGER.debug("[SSOptimizer] 无法遍历 Janino sourceFinder", e);
            return List.of();
        }
    }

    private static void collectSourceRoots(final Object sourceFinder,
                                           final Set<File> roots) throws IllegalAccessException {
        if (sourceFinder == null) {
            return;
        }
        if (sourceFinder instanceof DirectoryResourceFinder && DIRECTORY_FIELD != null) {
            final File directory = (File) DIRECTORY_FIELD.get(sourceFinder);
            if (directory != null) {
                roots.add(directory.getAbsoluteFile());
            }
            return;
        }
        if (sourceFinder instanceof MultiResourceFinder && RESOURCE_FINDERS_FIELD != null) {
            final Collection<?> resourceFinders = (Collection<?>) RESOURCE_FINDERS_FIELD.get(sourceFinder);
            if (resourceFinders == null) {
                return;
            }
            for (Object nestedFinder : resourceFinders) {
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
            } catch (IOException ignored) {
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
        // 分离模式的缓存产物是经过 GL owner 重定向的字节码，与普通模式字节码语义不同，
        // 必须分目录隔离，避免跨模式复用（普通模式读到改写产物会因 bridge 未安装而 ISE）
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

    private static ThreadFactory warmupThreadFactory() {
        final AtomicInteger threadCounter = new AtomicInteger(1);
        return runnable -> {
            final Thread thread = new Thread(runnable,
                    "SSOptimizer-JaninoWarmup-" + threadCounter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static WarmupState warmupState(final JavaSourceClassLoader loader) {
        synchronized (WARMUP_STATES) {
            return WARMUP_STATES.computeIfAbsent(loader, ignored -> new WarmupState(new AtomicBoolean(false), null));
        }
    }

    private static Field resolveField(final Class<?> owner,
                                      final String name) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            LOGGER.warn("[SSOptimizer] Failed to resolve Janino reflection field " + owner.getName() + '.' + name, e);
            return null;
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

    private static final class WarmupJavaSourceClassLoader extends JavaSourceClassLoader {
        private WarmupJavaSourceClassLoader(final ClassLoader parentLoader,
                                            final File[] sourcePath) {
            super(parentLoader, sourcePath, null);
        }

        @SuppressWarnings("unused")
        private Map<String, byte[]> ssoptimizer$generateBytecodesOriginal(final String className) throws ClassNotFoundException {
            return super.generateBytecodes(className);
        }
    }
}