package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.mapping.BytecodeRemapper;
import github.kasuminova.ssoptimizer.mapping.MappingDirection;
import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.MappingPlatform;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * 运行期字节码视图测试 fixture。
 * <p>
 * named-game-jars 全量改名（构建期全量表）后，classpath 上的 named jar 不再保留
 * 混淆成员名，不能再作为"运行期视图"的字节码来源。本 fixture 从
 * {@code game-jars/{platform}/} 下的入库 vendor jar（obf 视图）直接读取游戏类字节码，
 * 并按需用人工映射表（运行期权威表）换算出运行期 named 视图——与 agent 运行时
 * {@code RuntimeRemapTransformer} 的输入输出严格一致：
 * <ul>
 *     <li>{@link #readObfuscatedBytes}：游戏类加载进 JVM 时的原始 obf 字节码；</li>
 *     <li>{@link #readRuntimeNamedBytes}：运行期 remap 后的 named 视图字节码（ASM 处理器工作视图）。</li>
 * </ul>
 * <p>
 * 必须直接读 vendor jar 文件而不是走测试 classpath：scope 语义片段对未混淆类名
 * （如 {@code com/fs/starfarer/combat/CombatState}）做 identity 类映射后，named jar
 * 会在同一资源路径下提供成员名已被全量表改写的副本，classpath 资源查找会优先命中
 * named jar 而不是 vendor jar，导致读到的不再是 obf 视图。第三方类（janino、txw2 等）
 * 不在 vendor jar 中，仍从测试 classpath 读取。
 */
final class RuntimeViewFixtures {
    private static final TinyV2MappingRepository REPOSITORY = TinyV2MappingRepository.loadDefault();
    private static final BytecodeRemapper OBFUSCATED_TO_NAMED = new BytecodeRemapper(
            REPOSITORY, MappingDirection.OBFUSCATED_TO_NAMED);

    private static List<JarFile> vendorJars;

    private RuntimeViewFixtures() {
    }

    /**
     * 把 named 或 obf 内部名解析为运行时（obf）类名。
     * 人工表未覆盖的类（identity 类、第三方类）原样返回。
     *
     * @param internalName named 或 obf 内部名
     * @return 运行时类名
     */
    static String runtimeClassName(String internalName) {
        MappingEntry classEntry = REPOSITORY.findClassByNamedName(internalName).orElse(null);
        return classEntry != null ? classEntry.obfuscatedName() : internalName;
    }

    /**
     * 读取原始 obf 类字节码。
     * <p>
     * 游戏类（{@code com/fs/**}）从 {@code game-jars/{platform}/} 的 vendor jar 直接读取，
     * 避免 named jar 同名条目遮蔽；第三方类从测试 classpath 读取。
     *
     * @param internalName named 或 obf 内部名
     * @return 类字节码；不存在时返回 {@code null}
     */
    static byte[] readObfuscatedBytes(String internalName) {
        String obfuscatedName = runtimeClassName(internalName);
        if (obfuscatedName.startsWith("com/fs/")) {
            return readVendorBytes(obfuscatedName);
        }
        return readClasspathBytes(obfuscatedName);
    }

    /**
     * 读取运行期 named 视图类字节码（obf 字节码经人工表 remap）。
     *
     * @param internalName named 或 obf 内部名
     * @return named 视图字节码；classpath 上不存在时返回 {@code null}
     */
    static byte[] readRuntimeNamedBytes(String internalName) {
        byte[] obfuscatedBytes = readObfuscatedBytes(internalName);
        return obfuscatedBytes == null ? null : OBFUSCATED_TO_NAMED.remapClass(obfuscatedBytes).bytecode();
    }

    /**
     * 从 vendor jar 中读取类字节码。
     *
     * @param obfuscatedName obf 内部名
     * @return 类字节码；vendor jar 中不存在时返回 {@code null}
     */
    private static byte[] readVendorBytes(String obfuscatedName) {
        String entryName = obfuscatedName + ".class";
        for (JarFile jar : vendorJars()) {
            ZipEntry entry = jar.getEntry(entryName);
            if (entry == null) {
                continue;
            }
            try (InputStream input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            } catch (IOException exception) {
                throw new UncheckedIOException("读取 vendor jar 条目失败: " + jar.getName() + "!" + entryName, exception);
            }
        }
        return null;
    }

    /**
     * 打开 {@code game-jars/{platform}/} 下全部 vendor jar。
     *
     * @return vendor jar 列表
     */
    private static List<JarFile> vendorJars() {
        if (vendorJars != null) {
            return vendorJars;
        }
        String rootDir = System.getProperty("project.rootDir");
        if (rootDir == null || rootDir.isBlank()) {
            throw new IllegalStateException("缺少系统属性 project.rootDir，无法定位 vendor jar 目录（应由 :app:test 任务设置）");
        }
        Path vendorDir = Path.of(rootDir, "game-jars", MappingPlatform.current().id());
        if (!Files.isDirectory(vendorDir)) {
            throw new IllegalStateException("vendor jar 目录不存在: " + vendorDir);
        }
        try (var paths = Files.list(vendorDir)) {
            List<Path> jarPaths = paths
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
            if (jarPaths.isEmpty()) {
                throw new IllegalStateException("vendor jar 目录为空: " + vendorDir);
            }
            List<JarFile> opened = new java.util.ArrayList<>(jarPaths.size());
            for (Path jarPath : jarPaths) {
                opened.add(new JarFile(jarPath.toFile()));
            }
            vendorJars = List.copyOf(opened);
            return vendorJars;
        } catch (IOException exception) {
            throw new UncheckedIOException("打开 vendor jar 目录失败: " + vendorDir, exception);
        }
    }

    /**
     * 从测试 classpath 读取类字节码（第三方类）。
     *
     * @param internalName 内部名
     * @return 类字节码；classpath 上不存在时返回 {@code null}
     */
    private static byte[] readClasspathBytes(String internalName) {
        String resourcePath = internalName + ".class";
        try (InputStream input = RuntimeViewFixtures.class.getClassLoader().getResourceAsStream(resourcePath)) {
            return input != null ? input.readAllBytes() : null;
        } catch (IOException exception) {
            throw new UncheckedIOException("读取 classpath 资源失败: " + resourcePath, exception);
        }
    }
}
