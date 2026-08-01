package github.kasuminova.ssoptimizer.mapping.gen;

import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.MappingPlatform;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;
import github.kasuminova.ssoptimizer.mapping.gen.ScopeFragments.ScopeFragment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * scope 语义片段分层合并与冲突检测测试。
 * <p>
 * 锁定四条契约：
 * <ul>
 *     <li>scope 片段语义名覆盖占位名（未覆盖成员仍用占位名）；</li>
 *     <li>人工运行期表条目优先于 scope 片段；</li>
 *     <li>scope 间混淆 key / named 类名冲突被检测并指明两个 scope；</li>
 *     <li>scopes 目录为空（或不存在）时合并行为与无片段机制前完全一致。</li>
 * </ul>
 */
class ScopeFragmentMergingTest {
    private static final List<ClassStructure> EXAMPLE_CLASSES = List.of(new ClassStructure(
            "com/example/A",
            "java/lang/Object",
            List.of(),
            List.of(new ClassStructure.Member("a", "I", 1),
                    new ClassStructure.Member("b", "I", 1)),
            List.of(new ClassStructure.Member("<init>", "()V", 1),
                    new ClassStructure.Member("a", "()V", 1))));

    @TempDir
    Path tempDir;

    @Test
    void scopeFragmentsOverrideGeneratedPlaceholders() {
        TinyV2MappingRepository humanRepository = TinyV2MappingRepository.of(List.of());
        List<MappingEntry> scopeEntries = List.of(
                MappingEntry.classEntry("com/example/A", "com/example/Alpha").withComment("语义注释"),
                MappingEntry.fieldEntry("com/example/A", "com/example/Alpha", "a", "alphaField", "I"));

        List<MappingEntry> generated = new IntermediaryNameGenerator()
                .generate(EXAMPLE_CLASSES, humanRepository, java.util.Set.of());
        List<MappingEntry> merged = new FullMappingMerger()
                .merge(humanRepository.entries(), scopeEntries, generated);

        TinyV2MappingRepository mergedRepository = TinyV2MappingRepository.of(merged);
        // 类条目：scope 语义名覆盖占位名，注释保留。
        MappingEntry classEntry = mergedRepository.requireClassByObfuscatedName("com/example/A");
        assertEquals("com/example/Alpha", classEntry.namedName());
        assertEquals("语义注释", classEntry.comment());
        // scope 覆盖的字段使用语义名。
        assertEquals("alphaField",
                mergedRepository.requireFieldByObfuscatedName("com/example/A", "a").namedName());
        // 未覆盖的字段与方法仍用占位名。
        assertTrue(mergedRepository.requireFieldByObfuscatedName("com/example/A", "b").namedName().startsWith("f_"));
        assertTrue(mergedRepository.requireMethodByObfuscatedName("com/example/A", "a", "()V")
                .namedName().startsWith("m_"));
    }

    @Test
    void humanEntriesTakePriorityOverScopeFragments() {
        TinyV2MappingRepository humanRepository = TinyV2MappingRepository.of(List.of(
                MappingEntry.classEntry("com/example/A", "com/example/HumanName"),
                MappingEntry.fieldEntry("com/example/A", "com/example/HumanName", "a", "humanField", "I")));
        List<MappingEntry> scopeEntries = List.of(
                MappingEntry.classEntry("com/example/A", "com/example/ScopeName"),
                MappingEntry.fieldEntry("com/example/A", "com/example/ScopeName", "a", "scopeField", "I"),
                MappingEntry.fieldEntry("com/example/A", "com/example/ScopeName", "b", "scopeFieldB", "I"));

        List<MappingEntry> generated = new IntermediaryNameGenerator()
                .generate(EXAMPLE_CLASSES, humanRepository, java.util.Set.of());
        List<MappingEntry> merged = new FullMappingMerger()
                .merge(humanRepository.entries(), scopeEntries, generated);

        TinyV2MappingRepository mergedRepository = TinyV2MappingRepository.of(merged);
        // 人工类条目与人工字段优先于 scope 片段。
        assertEquals("com/example/HumanName",
                mergedRepository.requireClassByObfuscatedName("com/example/A").namedName());
        assertEquals("humanField",
                mergedRepository.requireFieldByObfuscatedName("com/example/A", "a").namedName());
        // 人工未覆盖的成员由 scope 语义名覆盖。
        assertEquals("scopeFieldB",
                mergedRepository.requireFieldByObfuscatedName("com/example/A", "b").namedName());
    }

    @Test
    void crossScopeConflictsAreDetected() {
        List<ScopeFragment> obfConflict = List.of(
                new ScopeFragment("scope-a", List.of(MappingEntry.classEntry("com/example/A", "com/example/Alpha"))),
                new ScopeFragment("scope-b", List.of(MappingEntry.classEntry("com/example/A", "com/example/Beta"))));
        List<String> obfConflicts = ScopeFragments.crossScopeConflictLines(obfConflict);
        assertEquals(1, obfConflicts.size(), "同一混淆类被两个 scope 映射应报一条冲突: " + obfConflicts);
        assertTrue(obfConflicts.get(0).contains("scope-a") && obfConflicts.get(0).contains("scope-b"),
                "冲突信息应指明两个 scope: " + obfConflicts.get(0));

        List<ScopeFragment> namedConflict = List.of(
                new ScopeFragment("scope-a", List.of(MappingEntry.classEntry("com/example/A", "com/example/Same"))),
                new ScopeFragment("scope-b", List.of(MappingEntry.classEntry("com/example/B", "com/example/Same"))));
        List<String> namedConflicts = ScopeFragments.crossScopeConflictLines(namedConflict);
        assertEquals(1, namedConflicts.size(), "同一 named 类名被两个 scope 使用应报一条冲突: " + namedConflicts);
        assertTrue(namedConflicts.get(0).contains("scope-a") && namedConflicts.get(0).contains("scope-b"),
                "冲突信息应指明两个 scope: " + namedConflicts.get(0));

        List<ScopeFragment> memberConflict = List.of(
                new ScopeFragment("scope-a", List.of(
                        MappingEntry.classEntry("com/example/A", "com/example/Alpha"),
                        MappingEntry.methodEntry("com/example/A", "com/example/Alpha", "a", "run", "()V"))),
                new ScopeFragment("scope-b", List.of(
                        MappingEntry.classEntry("com/example/B", "com/example/Beta"),
                        MappingEntry.methodEntry("com/example/A", "com/example/Alpha", "a", "execute", "()V"))));
        List<String> memberConflicts = ScopeFragments.crossScopeConflictLines(memberConflict);
        assertEquals(1, memberConflicts.size(), "同一混淆成员被两个 scope 映射应报一条冲突: " + memberConflicts);

        assertTrue(ScopeFragments.crossScopeConflictLines(List.of(
                new ScopeFragment("scope-a", List.of(MappingEntry.classEntry("com/example/A", "com/example/Alpha"))),
                new ScopeFragment("scope-b", List.of(MappingEntry.classEntry("com/example/B", "com/example/Beta"))))
        ).isEmpty(), "互不相交的 scope 不应报冲突");
    }

    @Test
    void candidateConflictsAgainstExistingFragmentsAreDetected() {
        List<ScopeFragment> existing = List.of(
                new ScopeFragment("scope-a", List.of(
                        MappingEntry.classEntry("com/example/A", "com/example/Alpha"),
                        MappingEntry.methodEntry("com/example/A", "com/example/Alpha", "a", "run", "()V"))),
                new ScopeFragment("scope-b", List.of(MappingEntry.classEntry("com/example/B", "com/example/Beta"))));

        // 候选片段重新声明既有 scope 的类：报混淆类冲突（即使 named 一致）+ named 冲突。
        ScopeFragment redeclareClass = new ScopeFragment("scope-c", List.of(
                MappingEntry.classEntry("com/example/A", "com/example/Alpha")));
        assertEquals(2, ScopeFragments.conflictLinesAgainst(existing, redeclareClass).size(),
                "重新声明既有类应报混淆类与 named 两条冲突");

        // 候选片段把既有 named 类名用于不同混淆类：只报 named 冲突。
        ScopeFragment reuseNamed = new ScopeFragment("scope-c", List.of(
                MappingEntry.classEntry("com/example/C", "com/example/Alpha")));
        List<String> namedConflicts = ScopeFragments.conflictLinesAgainst(existing, reuseNamed);
        assertEquals(1, namedConflicts.size(), "named 类名复用应报一条冲突: " + namedConflicts);
        assertTrue(namedConflicts.get(0).contains("scope-a") && namedConflicts.get(0).contains("scope-c"),
                "冲突信息应指明两个 scope: " + namedConflicts.get(0));

        // 候选片段映射既有 scope 已映射的成员：报成员冲突。
        ScopeFragment remapMember = new ScopeFragment("scope-c", List.of(
                MappingEntry.classEntry("com/example/C", "com/example/Gamma"),
                MappingEntry.methodEntry("com/example/A", "com/example/Alpha", "a", "execute", "()V")));
        assertEquals(1, ScopeFragments.conflictLinesAgainst(existing, remapMember).size(),
                "重复映射既有成员应报一条冲突");

        // 候选片段内部重复声明同一类：报冲突。
        ScopeFragment duplicateInside = new ScopeFragment("scope-c", List.of(
                MappingEntry.classEntry("com/example/C", "com/example/Gamma"),
                MappingEntry.classEntry("com/example/C", "com/example/Gamma")));
        assertEquals(1, ScopeFragments.conflictLinesAgainst(existing, duplicateInside).size(),
                "片段内部重复类声明应报一条冲突");

        // 与既有片段互不相交的候选：无冲突。
        ScopeFragment clean = new ScopeFragment("scope-c", List.of(
                MappingEntry.classEntry("com/example/C", "com/example/Gamma"),
                MappingEntry.methodEntry("com/example/C", "com/example/Gamma", "c", "walk",
                        "(Lcom/example/Alpha;)V")));
        assertTrue(ScopeFragments.conflictLinesAgainst(existing, clean).isEmpty(),
                "互不相交的候选片段不应报冲突（named 描述符应正常换算）");
    }

    @Test
    void memberExtensionFragmentsPassWithExactClassDeclaration() {
        List<ScopeFragment> existing = List.of(
                new ScopeFragment("scope-a", List.of(
                        MappingEntry.classEntry("com/example/A", "com/example/Alpha"),
                        MappingEntry.methodEntry("com/example/A", "com/example/Alpha", "a", "run", "()V"))));

        // obf + named 与既有声明完全一致的成员扩展片段：类级冲突豁免，无冲突。
        ScopeFragment extension = new ScopeFragment("wave3-p01", List.of(
                MappingEntry.classEntry("com/example/A", "com/example/Alpha"),
                MappingEntry.methodEntry("com/example/A", "com/example/Alpha", "b", "stop", "()V")));
        assertTrue(ScopeFragments.extensionAwareConflictLines(existing, extension).isEmpty(),
                "obf+named 一致的成员扩展不应报冲突");

        // 扩展片段重复映射归属 scope 已映射的成员：成员级冲突照常报。
        ScopeFragment extensionMemberConflict = new ScopeFragment("wave3-p01", List.of(
                MappingEntry.classEntry("com/example/A", "com/example/Alpha"),
                MappingEntry.methodEntry("com/example/A", "com/example/Alpha", "a", "execute", "()V")));
        assertEquals(1, ScopeFragments.extensionAwareConflictLines(existing, extensionMemberConflict).size(),
                "扩展片段与归属 scope 的成员冲突不得豁免");

        // 同 obf 类但 named 不同（抢注/改名）：混淆类冲突照常报，不得豁免。
        ScopeFragment renamedExtension = new ScopeFragment("wave3-p01", List.of(
                MappingEntry.classEntry("com/example/A", "com/example/OtherName")));
        assertEquals(1, ScopeFragments.extensionAwareConflictLines(existing, renamedExtension).size(),
                "同类异名的伪扩展不得豁免");
    }

    @Test
    void emptyScopesKeepExistingBehavior() throws Exception {
        // 不存在的目录与空目录都返回空片段列表。
        assertTrue(ScopeFragments.load(tempDir.resolve("scopes"), MappingPlatform.LINUX).isEmpty());
        Files.createDirectories(tempDir.resolve("scopes"));
        assertTrue(ScopeFragments.load(tempDir.resolve("scopes"), MappingPlatform.LINUX).isEmpty());

        // 空片段层与无片段层的合并输出字节一致。
        TinyV2MappingRepository humanRepository = TinyV2MappingRepository.of(List.of(
                MappingEntry.classEntry("com/example/A", "com/example/Alpha").withComment("人工注释"),
                MappingEntry.fieldEntry("com/example/A", "com/example/Alpha", "a", "alphaField", "I")));
        List<MappingEntry> generated = new IntermediaryNameGenerator()
                .generate(EXAMPLE_CLASSES, humanRepository, java.util.Set.of());
        FullMappingMerger merger = new FullMappingMerger();
        String withoutScopeLayer = merger.exportTiny(merger.merge(humanRepository.entries(), generated));
        String withEmptyScopeLayer = merger.exportTiny(merger.merge(humanRepository.entries(), List.of(), generated));
        assertEquals(withoutScopeLayer, withEmptyScopeLayer, "空 scope 层不得改变合并输出");
    }

    @Test
    void fragmentsLoadFromScopeDirectoryFiles() throws Exception {
        Path scopesDir = Files.createDirectories(tempDir.resolve("scopes"));
        Files.writeString(scopesDir.resolve("campaign-linux.tiny"),
                "tiny\t2\t0\tobf\tnamed\n"
                        + "c\tcom/example/A\tcom/example/Alpha\n"
                        + "\tc\t语义注释\n"
                        + "\tf\ta\talphaField\tI\n");
        // 其他平台的文件与无关文件应被忽略。
        Files.writeString(scopesDir.resolve("campaign-windows.tiny"),
                "tiny\t2\t0\tobf\tnamed\nc\tcom/example/W\tcom/example/WindowsAlpha\n");
        Files.writeString(scopesDir.resolve("README.md"), "说明文件\n");

        List<ScopeFragment> fragments = ScopeFragments.load(scopesDir, MappingPlatform.LINUX);
        assertEquals(1, fragments.size());
        assertEquals("campaign", fragments.get(0).scope());
        assertEquals(2, fragments.get(0).entries().size());
        MappingEntry classEntry = fragments.get(0).entries().get(0);
        assertEquals("com/example/Alpha", classEntry.namedName());
        assertEquals("语义注释", classEntry.comment(), "片段注释应随解析保留");
        assertEquals("alphaField", fragments.get(0).entries().get(1).namedName());
    }
}
