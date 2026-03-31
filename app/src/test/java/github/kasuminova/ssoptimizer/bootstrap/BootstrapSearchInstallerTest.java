package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BootstrapSearchInstallerTest {
    @TempDir
    Path tempDir;

    private static void copyClassEntry(JarOutputStream output, String entryName) throws IOException {
        output.putNextEntry(new JarEntry(entryName));
        try (InputStream input = BootstrapSearchInstallerTest.class.getClassLoader().getResourceAsStream(entryName)) {
            assertNotNull(input, "Missing class resource: " + entryName);
            input.transferTo(output);
        }
        output.closeEntry();
    }

    @AfterEach
    void tearDown() {
        BootstrapSearchInstaller.resetForTest();
    }

    @Test
    void resolveArchiveReturnsNullForExplodedClasses() {
        assertNull(BootstrapSearchInstaller.resolveArchive(getClass()));
    }

    @Test
    void appendsJarToBootstrapSearch() throws IOException {
        Path jarPath = tempDir.resolve("ssoptimizer-agent-test.jar");
        try (JarOutputStream ignored = new JarOutputStream(java.nio.file.Files.newOutputStream(jarPath))) {
            // Empty jar is enough for appendToBootstrapClassLoaderSearch contract testing.
        }

        AtomicReference<String> appendedJarName = new AtomicReference<>();
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("appendToBootstrapClassLoaderSearch")) {
                        appendedJarName.set(((JarFile) args[0]).getName());
                    }

                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    return null;
                }
        );

        BootstrapSearchInstaller.install(instrumentation, jarPath);

        assertEquals(jarPath.toAbsolutePath().normalize().toString(), appendedJarName.get());
    }

    @Test
    void createsMinimalBootstrapHelperJar() throws IOException {
        Path sourceJar = tempDir.resolve("ssoptimizer-agent-source.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(sourceJar))) {
            copyClassEntry(output, "github/kasuminova/ssoptimizer/bootstrap/ReflectionHelper.class");
            copyClassEntry(output, "github/kasuminova/ssoptimizer/bootstrap/NameTranslator.class");
        }

        Path helperJar = BootstrapSearchInstaller.createBootstrapHelperArchive(sourceJar);
        assertNotNull(helperJar);
        assertTrue(Files.isRegularFile(helperJar));

        try (JarFile jarFile = new JarFile(helperJar.toFile())) {
            assertNotNull(jarFile.getJarEntry("github/kasuminova/ssoptimizer/bootstrap/ReflectionHelper.class"));
            assertNotNull(jarFile.getJarEntry("github/kasuminova/ssoptimizer/bootstrap/NameTranslator.class"));
        }
    }
}