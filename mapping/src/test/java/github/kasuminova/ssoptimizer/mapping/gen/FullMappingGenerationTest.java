package github.kasuminova.ssoptimizer.mapping.gen;

import github.kasuminova.ssoptimizer.mapping.GameJarConsistency;
import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.MappingPlatform;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全量映射生成工作流测试。
 * <p>
 * 锁定三条契约：
 * <ul>
 *     <li>生成确定性——同一输入两次运行输出字节一致；</li>
 *     <li>人工条目优先——人工表覆盖的类/成员以人工名为准，注释保留；</li>
 *     <li>全量表可被 {@link TinyV2MappingRepository} 解析、覆盖全部混淆 jar 类，
 *     且逐条目通过 {@link GameJarConsistency} 的 jar 一致性校验（双平台，使用入库 vendor jar）。</li>
 * </ul>
 */
class FullMappingGenerationTest {

    @Test
    void generatedFullMappingIsDeterministicCompleteAndConsistentWithJars() throws Exception {
        for (MappingPlatform platform : MappingPlatform.values()) {
            String first = generateFullTiny(platform);
            String second = generateFullTiny(platform);
            assertEquals(first, second, "全量映射生成必须确定（两次运行字节一致）: " + platform.id());

            Path tempFile = Files.createTempFile("ssoptimizer-" + platform.id() + "-full", ".tiny");
            try {
                Files.writeString(tempFile, first);
                TinyV2MappingRepository fullRepository = TinyV2MappingRepository.loadFromFile(tempFile);

                List<ClassStructure> classes = scanPlatformClasses(platform);
                for (ClassStructure classStructure : classes) {
                    assertTrue(fullRepository.findClassByObfuscatedName(classStructure.name()).isPresent(),
                            "[" + platform.id() + "] 全量表缺少类映射: " + classStructure.name());
                }

                GameJarConsistency.assertConsistency(platform, fullRepository);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    @Test
    void humanEntriesTakePriorityOverGeneratedPlaceholders() {
        List<ClassStructure> classes = List.of(new ClassStructure(
                "com/example/A",
                "java/lang/Object",
                List.of(),
                List.of(new ClassStructure.Member("a", "I", 1),
                        new ClassStructure.Member("b", "Lcom/example/A;", 1)),
                List.of(new ClassStructure.Member("<init>", "()V", 1),
                        new ClassStructure.Member("<clinit>", "()V", 8),
                        new ClassStructure.Member("a", "()V", 1))));
        TinyV2MappingRepository humanRepository = TinyV2MappingRepository.of(List.of(
                MappingEntry.classEntry("com/example/A", "com/example/Alpha").withComment("人工注释"),
                MappingEntry.fieldEntry("com/example/A", "com/example/Alpha", "a", "alphaField", "I")));

        List<MappingEntry> generated = new IntermediaryNameGenerator()
                .generate(classes, humanRepository, java.util.Set.of());
        List<MappingEntry> merged = new FullMappingMerger().merge(humanRepository.entries(), generated);

        // 类条目：人工优先且注释保留。
        MappingEntry classEntry = merged.stream()
                .filter(entry -> entry.isClass() && entry.obfuscatedName().equals("com/example/A"))
                .findFirst().orElseThrow();
        assertEquals("com/example/Alpha", classEntry.namedName());
        assertEquals("人工注释", classEntry.comment());

        TinyV2MappingRepository mergedRepository = TinyV2MappingRepository.of(merged);
        // 人工字段优先。
        assertEquals("alphaField",
                mergedRepository.requireFieldByObfuscatedName("com/example/A", "a").namedName());
        // 未覆盖字段生成占位名，描述符换算为 named 存储。
        MappingEntry placeholderField = mergedRepository.requireFieldByObfuscatedName("com/example/A", "b");
        assertTrue(placeholderField.namedName().startsWith("f_"), "占位字段名应以 f_ 开头: " + placeholderField.namedName());
        assertEquals("Lcom/example/Alpha;", placeholderField.descriptor());
        assertNull(placeholderField.comment());
        // 未覆盖方法生成占位名，构造方法与静态初始化块不生成映射。
        assertTrue(mergedRepository.requireMethodByObfuscatedName("com/example/A", "a", "()V")
                .namedName().startsWith("m_"));
        assertTrue(mergedRepository.findMethodByObfuscatedName("com/example/A", "<init>", "()V").isEmpty());
        assertTrue(mergedRepository.findMethodByObfuscatedName("com/example/A", "<clinit>", "()V").isEmpty());
    }

    @Test
    void identityClassesKeepOriginalNamesAndMembers() {
        List<ClassStructure> classes = List.of(new ClassStructure(
                "com/example/Keep",
                "java/lang/Object",
                List.of(),
                List.of(new ClassStructure.Member("value", "I", 1)),
                List.of(new ClassStructure.Member("work", "()V", 1))));
        TinyV2MappingRepository humanRepository = TinyV2MappingRepository.of(List.of());
        TinyV2MappingRepository identityRepository = TinyV2MappingRepository.of(List.of(
                MappingEntry.classEntry("com/example/Keep", "com/example/Keep")));

        List<MappingEntry> generated = new IntermediaryNameGenerator()
                .generate(classes, humanRepository, java.util.Set.of("com/example/Keep"));
        assertTrue(generated.isEmpty(), "保持原名类不应生成任何占位条目: " + generated);

        List<MappingEntry> merged = new FullMappingMerger().merge(identityRepository.entries(), generated);
        assertEquals(1, merged.size());
        assertEquals("com/example/Keep", merged.get(0).namedName());
    }

    @Test
    void identityFragmentClassesKeepNamesInFullMapping() throws Exception {
        for (MappingPlatform platform : MappingPlatform.values()) {
            String tiny = generateFullTiny(platform);
            Path tempFile = Files.createTempFile("ssoptimizer-" + platform.id() + "-full", ".tiny");
            try {
                Files.writeString(tempFile, tiny);
                TinyV2MappingRepository fullRepository = TinyV2MappingRepository.loadFromFile(tempFile);
                assertEquals("com/fs/graphics/Sprite",
                        fullRepository.requireClassByObfuscatedName("com/fs/graphics/Sprite").namedName());
                assertEquals("com/fs/starfarer/campaign/fleet/CampaignFleetView",
                        fullRepository.requireClassByObfuscatedName("com/fs/starfarer/campaign/fleet/CampaignFleetView").namedName());
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private static String generateFullTiny(MappingPlatform platform) throws IOException {
        List<ClassStructure> classes = scanPlatformClasses(platform);
        TinyV2MappingRepository humanRepository = loadHumanRepository(platform);
        TinyV2MappingRepository identityRepository = loadIdentityRepository();
        List<MappingEntry> identityEntries = identityRepository == null ? List.of() : identityRepository.entries();
        java.util.Set<String> identityClasses = new java.util.HashSet<>();
        for (MappingEntry entry : identityEntries) {
            if (entry.isClass()) {
                identityClasses.add(entry.obfuscatedName());
            }
        }

        List<MappingEntry> generated = new IntermediaryNameGenerator().generate(classes, humanRepository, identityClasses);
        List<MappingEntry> priorityEntries = new java.util.ArrayList<>(humanRepository.entries());
        priorityEntries.addAll(identityEntries);
        List<MappingEntry> merged = new FullMappingMerger().merge(priorityEntries, generated);
        return new FullMappingMerger().exportTiny(merged);
    }

    private static List<ClassStructure> scanPlatformClasses(MappingPlatform platform) throws IOException {
        Path jarDir = GameJarConsistency.resolveGameJarDir(platform);
        try (Stream<Path> files = Files.list(jarDir)) {
            List<Path> jars = files
                    .filter(path -> path.getFileName().toString().endsWith("_obf.jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            return ClassStructure.scan(jars);
        }
    }

    private static TinyV2MappingRepository loadIdentityRepository() {
        String fileName = "ssoptimizer-identity.tiny";
        Path moduleRelative = Path.of("src", "main", "resources", "mappings", fileName);
        if (Files.isRegularFile(moduleRelative)) {
            return TinyV2MappingRepository.loadFromFile(moduleRelative);
        }
        Path rootRelative = Path.of("mapping", "src", "main", "resources", "mappings", fileName);
        if (Files.isRegularFile(rootRelative)) {
            return TinyV2MappingRepository.loadFromFile(rootRelative);
        }
        return null;
    }

    private static TinyV2MappingRepository loadHumanRepository(MappingPlatform platform) {
        String fileName = "ssoptimizer-" + platform.id() + ".tiny";
        Path moduleRelative = Path.of("src", "main", "resources", "mappings", fileName);
        if (Files.isRegularFile(moduleRelative)) {
            return TinyV2MappingRepository.loadFromFile(moduleRelative);
        }
        Path rootRelative = Path.of("mapping", "src", "main", "resources", "mappings", fileName);
        assertTrue(Files.isRegularFile(rootRelative), "未找到人工映射表: " + rootRelative.toAbsolutePath());
        return TinyV2MappingRepository.loadFromFile(rootRelative);
    }
}
