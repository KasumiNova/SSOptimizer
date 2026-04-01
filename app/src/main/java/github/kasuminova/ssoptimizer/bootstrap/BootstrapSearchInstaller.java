package github.kasuminova.ssoptimizer.bootstrap;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * 引导类搜索安装器，将必要的 Helper 类注入到引导类加载器的搜索路径中。
 * <p>
 * 由于 {@link ReflectionHelper} 等类需要在引导类加载器级别可见（被注入到游戏类的字节码会调用它们），
 * 此类从 Agent jar 中提取相关类文件并通过 {@link Instrumentation#appendToBootstrapClassLoaderSearch}
 * 加入引导类路径。
 */
final class BootstrapSearchInstaller {
    private static final Logger   LOGGER             = Logger.getLogger(BootstrapSearchInstaller.class);
    private static final String[] HELPER_ENTRY_NAMES = {
            "github/kasuminova/ssoptimizer/bootstrap/ReflectionHelper.class",
            "github/kasuminova/ssoptimizer/bootstrap/NameTranslator.class"
    };

    private static volatile JarFile appendedJar;
    private static volatile Path    appendedPath;
    private static volatile boolean helperVisibilityReady;

    private BootstrapSearchInstaller() {
    }

    /**
     * 使用默认锚点类安装引导 Helper。
     *
     * @param instrumentation JVM 提供的 {@link Instrumentation} 实例
     */
    static void install(Instrumentation instrumentation) {
        install(instrumentation, SSOptimizerAgent.class);
    }

    /**
     * 从指定锚点类解析 Agent jar 并安装引导 Helper。
     *
     * @param instrumentation JVM 提供的 {@link Instrumentation} 实例
     * @param anchorClass     用于定位 Agent jar 的锚点类
     */
    static void install(Instrumentation instrumentation, Class<?> anchorClass) {
        if (instrumentation == null) {
            return;
        }

        Path archive = resolveArchive(anchorClass);
        if (archive == null) {
            helperVisibilityReady = false;
            LOGGER.warn("[SSOptimizer] Bootstrap helper install skipped: agent archive unavailable");
            return;
        }

        Path helperArchive = createBootstrapHelperArchive(archive);
        if (helperArchive == null) {
            helperVisibilityReady = false;
            LOGGER.warn("[SSOptimizer] Bootstrap helper install skipped: helper archive creation failed");
            return;
        }

        install(instrumentation, helperArchive);
    }

    /**
     * 将指定 jar 文件追加到引导类加载器搜索路径。
     * <p>
     * 幂等操作：同一路径不会重复追加。
     *
     * @param instrumentation JVM 提供的 {@link Instrumentation} 实例
     * @param archive         要追加的 jar 文件路径
     */
    static synchronized void install(Instrumentation instrumentation, Path archive) {
        if (instrumentation == null || archive == null) {
            return;
        }

        Path normalizedArchive = archive.toAbsolutePath().normalize();
        if (!normalizedArchive.toString().endsWith(".jar") || !Files.isRegularFile(normalizedArchive)) {
            helperVisibilityReady = false;
            LOGGER.warn("[SSOptimizer] Bootstrap helper install skipped: not a jar archive");
            return;
        }

        if (normalizedArchive.equals(appendedPath)) {
            helperVisibilityReady = true;
            return;
        }

        try {
            JarFile jarFile = new JarFile(normalizedArchive.toFile());
            instrumentation.appendToBootstrapClassLoaderSearch(jarFile);
            appendedJar = jarFile;
            appendedPath = normalizedArchive;
            helperVisibilityReady = true;
            LOGGER.info("[SSOptimizer] Bootstrap helper visibility ready: " + normalizedArchive);
        } catch (IOException exception) {
            helperVisibilityReady = false;
            LOGGER.error("[SSOptimizer] Failed to append helper jar to bootstrap search", exception);
        }
    }

    /**
     * 从源 jar 中提取 Helper 类文件，创建临时 jar 归档。
     *
     * @param sourceArchive 源 Agent jar 路径
     * @return 临时 Helper jar 路径；失败则返回 {@code null}
     */
    static Path createBootstrapHelperArchive(Path sourceArchive) {
        if (sourceArchive == null) {
            return null;
        }

        Path normalizedArchive = sourceArchive.toAbsolutePath().normalize();
        if (!normalizedArchive.toString().endsWith(".jar") || !Files.isRegularFile(normalizedArchive)) {
            return null;
        }

        try (JarFile sourceJar = new JarFile(normalizedArchive.toFile())) {
            Path helperArchive = Files.createTempFile("ssoptimizer-bootstrap-helper-", ".jar");
            helperArchive.toFile().deleteOnExit();

            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(helperArchive))) {
                for (String entryName : HELPER_ENTRY_NAMES) {
                    copyRequiredEntry(sourceJar, output, entryName);
                }
            } catch (IOException | RuntimeException exception) {
                Files.deleteIfExists(helperArchive);
                throw exception;
            }

            return helperArchive;
        } catch (IOException | UncheckedIOException exception) {
            return null;
        }
    }

    /** 返回 Helper 类是否已在引导类加载器中可见。 */
    static boolean isHelperVisibilityReady() {
        return helperVisibilityReady;
    }

    /** 测试用：强制标记为已安装状态。 */
    static void forceInstalledForTest() {
        helperVisibilityReady = true;
    }

    private static void copyRequiredEntry(JarFile sourceJar, JarOutputStream output, String entryName) throws IOException {
        JarEntry sourceEntry = sourceJar.getJarEntry(entryName);
        if (sourceEntry == null) {
            throw new IOException("Missing bootstrap helper entry: " + entryName);
        }

        JarEntry targetEntry = new JarEntry(entryName);
        output.putNextEntry(targetEntry);
        try (InputStream input = sourceJar.getInputStream(sourceEntry)) {
            input.transferTo(output);
        }
        output.closeEntry();
    }

    /**
     * 从指定锚点类解析 Agent jar 文件路径。
     *
     * @param anchorClass 锚点类
     * @return jar 文件的绝对路径；若无法解析则返回 {@code null}
     */
    static Path resolveArchive(Class<?> anchorClass) {
        if (anchorClass == null) {
            return null;
        }

        try {
            CodeSource codeSource = anchorClass.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return null;
            }

            Path archive = Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
            if (!archive.toString().endsWith(".jar") || !Files.isRegularFile(archive)) {
                return null;
            }
            return archive;
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return null;
        }
    }

    static synchronized void resetForTest() {
        if (appendedJar != null) {
            try {
                appendedJar.close();
            } catch (IOException ignored) {
                // Best effort cleanup for tests.
            }
        }

        appendedJar = null;
        appendedPath = null;
        helperVisibilityReady = false;
    }
}