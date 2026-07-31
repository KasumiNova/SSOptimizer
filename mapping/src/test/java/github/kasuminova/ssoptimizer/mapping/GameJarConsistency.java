package github.kasuminova.ssoptimizer.mapping;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 映射表与游戏 jar 字节码一致性校验的测试工具。
 * <p>
 * 以 {@code game-jars/{platform}/} 下入库的游戏 jar 为唯一事实源建立
 * {@code 类名 → (字段 name:desc 集合, 方法 name:desc 集合)} 索引，
 * 供 {@link MappingVsGameJarConsistencyTest}（人工表）与全量表生成测试共用同一套断言。
 * 与宿主 OS 无关，CI 模式（无 starsector.gameDir）下始终使用 vendor jar。
 */
public final class GameJarConsistency {
    private GameJarConsistency() {
    }

    /**
     * 断言给定映射仓库的每一条目都真实存在于对应平台的 jar 中。
     * <p>
     * 成员按 {@code name + desc} 精确匹配（混淆成员名含非法标识符且同名重载极多）；
     * 描述符比对前做 named→obf 换算：表内类按表换算，表外类原样保留。
     *
     * @param platform   目标平台
     * @param repository 待校验的映射仓库
     */
    public static void assertConsistency(final MappingPlatform platform,
                                         final TinyV2MappingRepository repository) throws IOException {
        final Map<String, JarClassIndex> jarIndex = buildJarIndex(platform);
        final Map<String, String> namedToObfuscated = namedToObfuscatedClasses(repository);

        final List<String> failures = new ArrayList<>();
        for (final MappingEntry entry : repository.entries()) {
            switch (entry.kind()) {
                case CLASS -> {
                    if (!jarIndex.containsKey(entry.obfuscatedName())) {
                        failures.add("[" + platform.id() + "] 类缺失: "
                                + entry.namedName() + " (表中混淆类名: " + entry.obfuscatedName() + ")");
                    }
                }
                case FIELD -> verifyMember(platform, jarIndex, namedToObfuscated, failures, entry, true);
                case METHOD -> verifyMember(platform, jarIndex, namedToObfuscated, failures, entry, false);
            }
        }

        assertTrue(failures.isEmpty(),
                "映射表与游戏 jar 不一致 (" + platform.id() + "):\n - " + String.join("\n - ", failures));
    }

    /**
     * 建立平台 jar 的类成员索引。
     *
     * @param platform 目标平台
     * @return 类名 → 成员索引
     */
    public static Map<String, JarClassIndex> buildJarIndex(final MappingPlatform platform) throws IOException {
        final Path jarDir = resolveGameJarDir(platform);
        final Map<String, JarClassIndex> index = new HashMap<>();
        try (Stream<Path> jars = Files.list(jarDir)) {
            for (final Path jar : jars.filter(path -> path.getFileName().toString().endsWith(".jar")).toList()) {
                try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar.toFile())) {
                    final var entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        final var jarEntry = entries.nextElement();
                        if (jarEntry.isDirectory() || !jarEntry.getName().endsWith(".class")) {
                            continue;
                        }
                        try (InputStream stream = jarFile.getInputStream(jarEntry)) {
                            final ClassReader reader = new ClassReader(stream);
                            final ClassNode node = new ClassNode();
                            reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                            final JarClassIndex classIndex = index.computeIfAbsent(node.name, name -> new JarClassIndex());
                            for (final FieldNode fieldNode : node.fields) {
                                classIndex.fields.add(fieldNode.name + ":" + fieldNode.desc);
                            }
                            for (final MethodNode methodNode : node.methods) {
                                classIndex.methods.add(methodNode.name + ":" + methodNode.desc);
                            }
                        }
                    }
                }
            }
        }
        return index;
    }

    /**
     * 解析平台游戏 jar 目录（模块或根目录相对路径均可，便于在不同工作目录下跑测试）。
     *
     * @param platform 目标平台
     * @return jar 目录
     */
    public static Path resolveGameJarDir(final MappingPlatform platform) {
        final Path moduleRelative = Path.of("..", "game-jars", platform.id());
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative;
        }
        final Path rootRelative = Path.of("game-jars", platform.id());
        assertTrue(Files.isDirectory(rootRelative),
                "未找到游戏 jar 目录: " + moduleRelative.toAbsolutePath() + " 或 " + rootRelative.toAbsolutePath());
        return rootRelative;
    }

    private static Map<String, String> namedToObfuscatedClasses(final TinyV2MappingRepository repository) {
        final Map<String, String> namedToObfuscated = new HashMap<>();
        for (final MappingEntry entry : repository.entries()) {
            if (entry.isClass()) {
                namedToObfuscated.put(entry.namedName(), entry.obfuscatedName());
            }
        }
        return namedToObfuscated;
    }

    /**
     * 把描述符中的表内 named 类引用换算为混淆类名，表外类与已是混淆名的引用原样保留。
     */
    private static String toObfuscatedDescriptor(final String descriptor, final Map<String, String> namedToObfuscated) {
        if (descriptor == null || descriptor.indexOf('L') < 0) {
            return descriptor;
        }

        final StringBuilder builder = new StringBuilder(descriptor.length());
        int cursor = 0;
        while (cursor < descriptor.length()) {
            final char current = descriptor.charAt(cursor);
            if (current != 'L') {
                builder.append(current);
                cursor++;
                continue;
            }

            final int end = descriptor.indexOf(';', cursor);
            assertTrue(end >= 0, "描述符格式不正确: " + descriptor);
            final String internalName = descriptor.substring(cursor + 1, end);
            builder.append('L').append(namedToObfuscated.getOrDefault(internalName, internalName)).append(';');
            cursor = end + 1;
        }
        return builder.toString();
    }

    private static void verifyMember(final MappingPlatform platform,
                                     final Map<String, JarClassIndex> jarIndex,
                                     final Map<String, String> namedToObfuscated,
                                     final List<String> failures,
                                     final MappingEntry entry,
                                     final boolean field) {
        final JarClassIndex owner = jarIndex.get(entry.ownerObfuscatedName());
        final String kindLabel = field ? "字段" : "方法";
        if (owner == null) {
            failures.add("[" + platform.id() + "] " + kindLabel + " owner 类缺失: "
                    + entry.ownerNamedName() + '#' + entry.namedName()
                    + " (表中混淆 owner: " + entry.ownerObfuscatedName() + ")");
            return;
        }

        final String obfuscatedDescriptor = toObfuscatedDescriptor(entry.descriptor(), namedToObfuscated);
        final String expected = entry.obfuscatedName() + ":" + obfuscatedDescriptor;
        final Set<String> actual = field ? owner.fields : owner.methods;
        if (actual.contains(expected)) {
            return;
        }

        failures.add("[" + platform.id() + "] " + kindLabel + "缺失: "
                + entry.ownerNamedName() + '#' + entry.namedName()
                + " (表中混淆成员: " + entry.obfuscatedName() + ", 换算后描述符: " + obfuscatedDescriptor + ")\n"
                + "    jar 中该类的候选成员:\n    - "
                + String.join("\n    - ", new TreeSet<>(actual)));
    }

    /** 单个类的 jar 成员索引（字段/方法均为 {@code name:desc} 集合）。 */
    public static final class JarClassIndex {
        /** 字段 name:desc 集合。 */
        public final Set<String> fields = new HashSet<>();
        /** 方法 name:desc 集合。 */
        public final Set<String> methods = new HashSet<>();
    }
}
