package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.mapping.BytecodeRemapper;
import github.kasuminova.ssoptimizer.mapping.MappingDirection;
import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * 预重映射类路径安装器。
 * <p>
 * 对映射中类名发生变化的条目（obfuscated ≠ named），从系统类路径的游戏 JAR 中读取
 * obfuscated 字节码，经由 {@link BytecodeRemapper} 重映射后以 named 路径写入临时 JAR，
 * 再追加到系统类加载器搜索路径。这样 JVM 在解析 named 类名（如
 * {@code com.fs.graphics.font.BitmapFontRenderer}）时能在该 JAR 中找到对应的 {@code .class}
 * 文件，避免 {@code ClassNotFoundException}。
 * <p>
 * 对于类名不变的映射条目（只有字段/方法重命名），仍由 {@link RuntimeRemapTransformer}
 * 在类加载时实时重映射。
 */
public final class RemappedClasspathInstaller {
    public static final String EXPORT_DIR_PROPERTY = "ssoptimizer.remappedclasspath.exportdir";

    private static final Logger LOGGER = Logger.getLogger(RemappedClasspathInstaller.class);

    private RemappedClasspathInstaller() {
    }

    /**
     * 扫描映射中类名发生变化的条目，构建 remapped JAR 并追加到系统类路径。
     *
     * @param inst JVM 提供的 {@link Instrumentation} 实例
     */
    public static void install(Instrumentation inst) {
        TinyV2MappingRepository repo = TinyV2MappingRepository.loadDefault();
        List<RenamedClassEntry> renamedClasses = findRenamedClasses(repo);
        if (renamedClasses.isEmpty()) {
            LOGGER.info("[SSOptimizer] No renamed class mappings found — skipping classpath patch");
            return;
        }

        BytecodeRemapper remapper = new BytecodeRemapper(repo, MappingDirection.OBFUSCATED_TO_NAMED);
        List<JarFile> gameJars = findGameJarsOnClasspath();
        if (gameJars.isEmpty()) {
            LOGGER.warn("[SSOptimizer] No game JARs found on classpath — cannot build remapped JAR");
            return;
        }

        try {
            Path remappedJar = buildRemappedJar(renamedClasses, remapper, gameJars);
            if (remappedJar == null) {
                LOGGER.warn("[SSOptimizer] Remapped JAR was empty — no renamed classes resolved");
                return;
            }
            exportRuntimeRemappedJarIfConfigured(remappedJar);
            JarFile jarFile = new JarFile(remappedJar.toFile());
            inst.appendToSystemClassLoaderSearch(jarFile);
            LOGGER.info("[SSOptimizer] Remapped classpath installed: " + remappedJar
                    + " (" + renamedClasses.size() + " renamed classes)");
        } catch (IOException e) {
            LOGGER.error("[SSOptimizer] Failed to install remapped classpath", e);
        } finally {
            for (JarFile jar : gameJars) {
                try {
                    jar.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 从映射仓库中找出所有类名发生变化的条目。
     *
     * @param repo 映射仓库
     * @return 类名变化条目列表（obfuscated 内部名 → named 内部名）
     */
    static List<RenamedClassEntry> findRenamedClasses(TinyV2MappingRepository repo) {
        List<RenamedClassEntry> result = new ArrayList<>();
        for (MappingEntry entry : repo.entries()) {
            if (!entry.isClass()) {
                continue;
            }
            String obf = entry.obfuscatedName();
            String named = entry.namedName();
            if (!obf.equals(named)) {
                result.add(new RenamedClassEntry(obf, named));
            }
        }
        return result;
    }

    /**
     * 在系统类路径上查找游戏 JAR 文件。
     * <p>
     * 通过 {@code java.class.path} 系统属性解析，过滤出实际存在的 {@code .jar} 文件。
     *
     * @return 打开的 JarFile 列表（调用方负责关闭）
     */
    private static List<JarFile> findGameJarsOnClasspath() {
        String classpath = System.getProperty("java.class.path", "");
        String[] entries = classpath.split(System.getProperty("path.separator", ":"));
        List<JarFile> jars = new ArrayList<>();
        for (String entry : entries) {
            if (!entry.endsWith(".jar")) {
                continue;
            }
            Path jarPath = Path.of(entry).toAbsolutePath().normalize();
            if (!Files.isRegularFile(jarPath)) {
                continue;
            }
            try {
                jars.add(new JarFile(jarPath.toFile()));
            } catch (IOException e) {
                LOGGER.warn("[SSOptimizer] Cannot open classpath JAR: " + jarPath, e);
            }
        }
        return jars;
    }

    /**
     * 构建包含 remapped 字节码的临时 JAR。
     * <p>
     * 对于每个类名变更条目，从游戏 JAR 中读取 obfuscated 字节码，remap 后以 named 路径写入。
     *
     * @param renamedClasses 类名变更列表
     * @param remapper       字节码重映射器
     * @param gameJars       游戏 JAR 文件列表
     * @return 临时 JAR 路径；若无任何类被成功 remap 则返回 {@code null}
     * @throws IOException 写入 JAR 时发生的 I/O 错误
     */
    private static Path buildRemappedJar(List<RenamedClassEntry> renamedClasses,
                                         BytecodeRemapper remapper,
                                         List<JarFile> gameJars) throws IOException {
        Path tmpJar = Files.createTempFile("ssoptimizer-remapped-classes-", ".jar");
        tmpJar.toFile().deleteOnExit();

        int count = 0;
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(tmpJar))) {
            for (RenamedClassEntry renamed : renamedClasses) {
                String obfPath = renamed.obfInternalName() + ".class";
                byte[] obfBytecode = readFromJars(gameJars, obfPath);
                if (obfBytecode == null) {
                    LOGGER.warn("[SSOptimizer] Cannot find obfuscated class on classpath: " + obfPath);
                    continue;
                }

                BytecodeRemapper.RemappedClass result = remapper.remapClass(obfBytecode);
                byte[] remappedBytecode = result.modified() ? result.bytecode() : obfBytecode;

                String namedPath = renamed.namedInternalName() + ".class";
                out.putNextEntry(new JarEntry(namedPath));
                out.write(remappedBytecode);
                out.closeEntry();
                count++;

                LOGGER.debug("[SSOptimizer] Remapped: " + renamed.obfInternalName()
                        + " -> " + renamed.namedInternalName());
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmpJar);
            throw e;
        }

        if (count == 0) {
            Files.deleteIfExists(tmpJar);
            return null;
        }
        return tmpJar;
    }

    /**
     * 从多个 JAR 中查找指定路径的 class 文件字节码。
     *
     * @param jars      JAR 文件列表
     * @param entryPath JAR 内的条目路径
     * @return 字节码；未找到返回 {@code null}
     */
    private static byte[] readFromJars(List<JarFile> jars, String entryPath) {
        for (JarFile jar : jars) {
            JarEntry entry = jar.getJarEntry(entryPath);
            if (entry == null) {
                continue;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            } catch (IOException e) {
                LOGGER.warn("[SSOptimizer] Failed to read " + entryPath + " from " + jar.getName(), e);
            }
        }
        return null;
    }

    /**
     * 在启用导出开关时，把运行时生成的 remapped JAR 复制到指定目录，便于直接检查
     * 实际参与类加载的 mapped class 结果。
     *
     * @param remappedJar 运行时生成的临时 remapped JAR
     */
    static void exportRuntimeRemappedJarIfConfigured(final Path remappedJar) {
        final String exportDir = System.getProperty(EXPORT_DIR_PROPERTY);
        if (exportDir == null || exportDir.isBlank() || remappedJar == null || !Files.isRegularFile(remappedJar)) {
            return;
        }

        try {
            final Path exportDirectory = Path.of(exportDir).toAbsolutePath().normalize();
            Files.createDirectories(exportDirectory);
            final Path exportTarget = exportDirectory.resolve("ssoptimizer-runtime-remapped.jar");
            Files.copy(remappedJar, exportTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[SSOptimizer] Exported runtime remapped JAR to " + exportTarget);
        } catch (IOException e) {
            LOGGER.warn("[SSOptimizer] Failed to export runtime remapped JAR", e);
        }
    }

    /**
     * 类名变更条目。
     *
     * @param obfInternalName   混淆内部名（斜线分隔）
     * @param namedInternalName 可读内部名（斜线分隔）
     */
    record RenamedClassEntry(String obfInternalName, String namedInternalName) {
    }
}
