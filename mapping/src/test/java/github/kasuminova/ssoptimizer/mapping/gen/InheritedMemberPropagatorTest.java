package github.kasuminova.ssoptimizer.mapping.gen;

import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InheritedMemberPropagator} 的逻辑验证：混淆器会把继承成员的引用挂在子类 owner 上，
 * 传播器必须为每个子类补齐继承成员的别名条目，否则 named jar 运行期抛
 * {@link NoSuchFieldError} / {@link NoSuchMethodError}。
 */
class InheritedMemberPropagatorTest {
    private static ClassStructure.Member field(String name, String desc) {
        return new ClassStructure.Member(name, desc, 0);
    }

    private static ClassStructure.Member method(String name, String desc) {
        return new ClassStructure.Member(name, desc, 0);
    }

    @Test
    void propagatesAncestorMembersIntoSubclass() {
        List<ClassStructure> classes = List.of(
                new ClassStructure("a/A", "java/lang/Object", List.of(),
                        List.of(field("fa", "I")),
                        List.of(method("ma", "()V"), method("<init>", "()V"))),
                new ClassStructure("a/B", "a/A", List.of(), List.of(), List.of()));
        List<MappingEntry> merged = List.of(
                MappingEntry.classEntry("a/A", "named/A"),
                MappingEntry.fieldEntry("a/A", "named/A", "fa", "fieldA", "I"),
                MappingEntry.methodEntry("a/A", "named/A", "ma", "methodA", "()V"),
                MappingEntry.methodEntry("a/A", "named/A", "<init>", "<init>", "()V"),
                MappingEntry.classEntry("a/B", "named/B"));

        List<MappingEntry> result = InheritedMemberPropagator.propagate(merged, classes);

        List<MappingEntry> bMembers = result.stream()
                .filter(entry -> !entry.isClass() && "a/B".equals(entry.ownerObfuscatedName()))
                .toList();
        assertEquals(2, bMembers.size(), "子类应补齐继承的字段与方法别名, 构造器不传播");
        assertTrue(bMembers.stream().anyMatch(entry -> entry.isField()
                && "fa".equals(entry.obfuscatedName()) && "fieldA".equals(entry.namedName())));
        assertTrue(bMembers.stream().anyMatch(entry -> entry.isMethod()
                && "ma".equals(entry.obfuscatedName()) && "methodA".equals(entry.namedName())));
        assertTrue(bMembers.stream().allMatch(entry -> "named/B".equals(entry.ownerNamedName())));
        assertTrue(bMembers.stream().allMatch(entry -> entry.comment() != null
                && entry.comment().contains("继承传播")));
    }

    @Test
    void subclassDeclarationWinsOverPropagation() {
        List<ClassStructure> classes = List.of(
                new ClassStructure("a/A", "java/lang/Object", List.of(),
                        List.of(field("fa", "I")), List.of()),
                new ClassStructure("a/B", "a/A", List.of(),
                        List.of(field("fa", "I")), List.of()));
        List<MappingEntry> merged = List.of(
                MappingEntry.classEntry("a/A", "named/A"),
                MappingEntry.fieldEntry("a/A", "named/A", "fa", "fieldA", "I"),
                MappingEntry.classEntry("a/B", "named/B"),
                MappingEntry.fieldEntry("a/B", "named/B", "fa", "shadowedField", "I"));

        List<MappingEntry> result = InheritedMemberPropagator.propagate(merged, classes);

        List<MappingEntry> bMembers = result.stream()
                .filter(entry -> !entry.isClass() && "a/B".equals(entry.ownerObfuscatedName()))
                .toList();
        assertEquals(1, bMembers.size(), "子类已声明同名成员时不得传播覆盖");
        assertEquals("shadowedField", bMembers.get(0).namedName());
    }

    @Test
    void propagatesAcrossInterfaceAndTransitiveAncestors() {
        List<ClassStructure> classes = List.of(
                new ClassStructure("a/I", null, List.of(), List.of(),
                        List.of(method("mi", "()V"))),
                new ClassStructure("a/A", "java/lang/Object", List.of("a/I"), List.of(), List.of()),
                new ClassStructure("a/B", "a/A", List.of(), List.of(), List.of()));
        List<MappingEntry> merged = List.of(
                MappingEntry.classEntry("a/I", "named/I"),
                MappingEntry.methodEntry("a/I", "named/I", "mi", "interfaceMethod", "()V"),
                MappingEntry.classEntry("a/A", "named/A"),
                MappingEntry.classEntry("a/B", "named/B"));

        List<MappingEntry> result = InheritedMemberPropagator.propagate(merged, classes);

        assertTrue(result.stream().anyMatch(entry -> "a/A".equals(entry.ownerObfuscatedName())
                && "mi".equals(entry.obfuscatedName()) && "interfaceMethod".equals(entry.namedName())));
        assertTrue(result.stream().anyMatch(entry -> "a/B".equals(entry.ownerObfuscatedName())
                && "mi".equals(entry.obfuscatedName()) && "interfaceMethod".equals(entry.namedName())),
                "接口方法应沿 implements 与继承链传播到间接子类");
    }

    @Test
    void propagatedEntriesStayInsideTheirClassBlock() {
        List<ClassStructure> classes = List.of(
                new ClassStructure("a/A", "java/lang/Object", List.of(),
                        List.of(field("fa", "I")), List.of()),
                new ClassStructure("a/B", "a/A", List.of(), List.of(), List.of()),
                new ClassStructure("a/C", "java/lang/Object", List.of(), List.of(), List.of()));
        List<MappingEntry> merged = List.of(
                MappingEntry.classEntry("a/A", "named/A"),
                MappingEntry.fieldEntry("a/A", "named/A", "fa", "fieldA", "I"),
                MappingEntry.classEntry("a/B", "named/B"),
                MappingEntry.classEntry("a/C", "named/C"));

        List<MappingEntry> result = InheritedMemberPropagator.propagate(merged, classes);

        int bClassIndex = indexOfClass(result, "a/B");
        int cClassIndex = indexOfClass(result, "a/C");
        int aliasIndex = -1;
        for (int i = 0; i < result.size(); i++) {
            MappingEntry entry = result.get(i);
            if (!entry.isClass() && "a/B".equals(entry.ownerObfuscatedName())) {
                aliasIndex = i;
            }
        }
        assertTrue(aliasIndex > bClassIndex && aliasIndex < cClassIndex,
                "传播条目必须位于所属类块内, 否则 Tiny 解析会挂错类");
    }

    private static int indexOfClass(List<MappingEntry> entries, String obfuscatedName) {
        for (int i = 0; i < entries.size(); i++) {
            MappingEntry entry = entries.get(i);
            if (entry.isClass() && obfuscatedName.equals(entry.obfuscatedName())) {
                return i;
            }
        }
        return -1;
    }
}
