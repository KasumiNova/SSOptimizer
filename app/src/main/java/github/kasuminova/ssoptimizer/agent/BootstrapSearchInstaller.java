package github.kasuminova.ssoptimizer.agent;

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

final class BootstrapSearchInstaller {
    private static final Logger   LOGGER             = Logger.getLogger(BootstrapSearchInstaller.class);
    private static final String[] HELPER_ENTRY_NAMES = {
            "github/kasuminova/ssoptimizer/agent/ReflectionHelper.class",
            "github/kasuminova/ssoptimizer/agent/NameTranslator.class"
    };

    private static volatile JarFile appendedJar;
    private static volatile Path    appendedPath;
    private static volatile boolean helperVisibilityReady;

    private BootstrapSearchInstaller() {
    }

    static void install(Instrumentation instrumentation) {
        install(instrumentation, SSOptimizerAgent.class);
    }

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

    static boolean isHelperVisibilityReady() {
        return helperVisibilityReady;
    }

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