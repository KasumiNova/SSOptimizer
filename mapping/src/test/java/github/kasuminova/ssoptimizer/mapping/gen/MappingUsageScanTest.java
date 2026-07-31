package github.kasuminova.ssoptimizer.mapping.gen;

import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 映射使用度扫描器测试。
 * <p>
 * 用 ASM 现场生成消费侧字节码（对游戏类/成员的典型引用形态），
 * 锁定引用收集与 语义命名 / 保持原名 / 提升名 / 占位名 四分类契约。
 */
class MappingUsageScanTest {

    @Test
    void referencesAreCollectedAndClassifiedByLayer() throws Exception {
        // 语义层：人工表（类 + 成员）。
        List<MappingEntry> semanticEntries = List.of(
                MappingEntry.classEntry("com/example/A", "com/example/Alpha"),
                MappingEntry.methodEntry("com/example/A", "com/example/Alpha", "a", "alphaMethod", "()V"),
                MappingEntry.fieldEntry("com/example/A", "com/example/Alpha", "b", "betaField", "I"));
        Set<String> identityClasses = Set.of("com/example/Keep");

        // 全量表 = 语义层 + identity 类 + 生成层（提升名 render / 占位名 m_1a2b3c4d、f_abcd1234）。
        List<MappingEntry> fullEntries = new java.util.ArrayList<>(semanticEntries);
        fullEntries.add(MappingEntry.classEntry("com/example/Keep", "com/example/Keep"));
        fullEntries.add(MappingEntry.classEntry("com/example/R", "com/example/R"));
        fullEntries.add(MappingEntry.methodEntry("com/example/R", "com/example/R", "render", "render", "()V"));
        fullEntries.add(MappingEntry.methodEntry("com/example/R", "com/example/R", "o00000", "m_1a2b3c4d", "()V"));
        fullEntries.add(MappingEntry.classEntry("com/example/C", "com/example/C_abcd1234"));
        fullEntries.add(MappingEntry.fieldEntry("com/example/C", "com/example/C_abcd1234", "x", "f_abcd1234", "I"));
        TinyV2MappingRepository fullRepository = TinyV2MappingRepository.of(fullEntries);

        Path inputDir = Files.createTempDirectory("usage-scan");
        try {
            Path classFile = inputDir.resolve("app/Consumer.class");
            Files.createDirectories(classFile.getParent());
            Files.write(classFile, consumerClassBytes());

            MappingUsageScanner.UsageScanResult result = new MappingUsageScanner()
                    .scan(List.of(inputDir), fullRepository, semanticEntries, identityClasses);

            // 类引用收集：含 LDC 类名字符串常量命中的表外不计、表内计。
            assertTrue(result.referencedClasses().containsKey("com/example/Alpha"));
            assertTrue(result.referencedClasses().containsKey("com/example/Keep"));
            assertTrue(result.referencedClasses().containsKey("com/example/R"));
            assertTrue(result.referencedClasses().containsKey("com/example/C_abcd1234"));

            // 占位名引用 = 违规清单（字段 + 方法各一条）。
            assertEquals(2, result.violations().size(), "占位名引用应全部列入违规: " + result.violations());
            assertTrue(result.violations().stream().anyMatch(line -> line.contains("com/example/R#m#m_1a2b3c4d")));
            assertTrue(result.violations().stream().anyMatch(line -> line.contains("com/example/C_abcd1234#f#f_abcd1234")));

            // 提升名引用单列（建议迁人工表），identity 与语义层不混入。
            assertEquals(List.of("com/example/R#m#render x1"), result.promotedReferences());
            assertTrue(result.reportLines().stream().anyMatch(line -> line.contains("语义命名引用: 2")),
                    "语义层成员引用应计 2 条: " + result.reportLines());
            assertTrue(result.reportLines().stream().anyMatch(line -> line.contains("保持原名类成员引用: 1")),
                    "identity 成员引用应计 1 条: " + result.reportLines());
        } finally {
            try (var files = Files.walk(inputDir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /**
     * 生成消费侧类 {@code app/Consumer.run()} 字节码：
     * 覆盖方法调用、字段访问、identity 成员、提升名、占位名与类名字符串常量引用。
     */
    private static byte[] consumerClassBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "app/Consumer", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/Alpha", "alphaMethod", "()V", false);
        method.visitFieldInsn(Opcodes.GETSTATIC, "com/example/Alpha", "betaField", "I");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/Keep", "work", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/R", "render", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/R", "m_1a2b3c4d", "()V", false);
        method.visitFieldInsn(Opcodes.GETSTATIC, "com/example/C_abcd1234", "f_abcd1234", "I");
        method.visitLdcInsn("com.fs.starfarer.combat.CombatEngine");
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
