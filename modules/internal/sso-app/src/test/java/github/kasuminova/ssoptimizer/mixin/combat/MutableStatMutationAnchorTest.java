package github.kasuminova.ssoptimizer.mixin.combat;

import com.fs.starfarer.api.combat.MutableStatWithTempMods;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutableStatMutationAnchorTest {


    @Test
    void everyRedirectTargetMethodExistsAndWritesTrue() throws IOException {
        final ClassNode node = readClasspathClass("com/fs/starfarer/api/combat/MutableStat");

        for (final String desc : redirectTargetMethods()) {
            final String name = desc.substring(0, desc.indexOf('('));
            final MethodNode method = findMethod(node, name, desc.substring(desc.indexOf('(')));
            assertNotNull(method, "MutableStat." + desc + " 必须存在（代际 redirect 目标）");
            assertFalse(trueWrites(method).isEmpty(),
                    "MutableStat." + desc + " 必须含 needsRecompute = true 赋值点");
        }
    }

    @Test
    void redirectTargetListIsExhaustive() throws IOException {
        final ClassNode node = readClasspathClass("com/fs/starfarer/api/combat/MutableStat");
        final Set<String> expected = new HashSet<>(redirectTargetMethods());

        final Set<String> actual = new HashSet<>();
        for (final MethodNode method : node.methods) {
            if (!trueWrites(method).isEmpty()) {
                actual.add(method.name + method.desc);
            }
        }
        assertEquals(expected, actual, "needsRecompute = true 赋值方法集合必须与 redirect 目标一致");

        final Set<String> allowedFalseWriters = new HashSet<>(Arrays.asList("recompute()V", "<init>(F)V"));
        for (final MethodNode method : node.methods) {
            if (falseWrites(method) > 0) {
                assertTrue(allowedFalseWriters.contains(method.name + method.desc),
                        "不允许的 needsRecompute = false 写入: " + method.name + method.desc);
            }
        }
    }

    @Test
    void subclassHasNoOwnNeedsRecomputeWrites() throws IOException {
        final ClassNode node = readClasspathClass("com/fs/starfarer/api/combat/MutableStatWithTempMods");
        for (final MethodNode method : node.methods) {
            assertTrue(trueWrites(method).isEmpty(),
                    "MutableStatWithTempMods." + method.name + method.desc + " 不得自有 needsRecompute 写入");
        }
    }

    @Test
    void advanceWithoutExpiryKeepsValueBitStable() {
        final MutableStatWithTempMods stat = new MutableStatWithTempMods(0.0F);
        stat.addTemporaryModFlat(10.0F, "sell_1", 10.0F);
        final int bits = Float.floatToIntBits(stat.getModifiedValue());

        stat.advance(1.0F);
        stat.advance(1.0F);

        assertEquals(bits, Float.floatToIntBits(stat.getModifiedValue()));
    }

    @Test
    void expiryAndRealOverwriteChangeValueButSameValueOverwriteDoesNot() {
        final MutableStatWithTempMods stat = new MutableStatWithTempMods(0.0F);
        stat.modifyFlat("stockpile", 50.0F);
        final int before = Float.floatToIntBits(stat.getModifiedValue());

        stat.modifyFlat("stockpile", 50.0F);
        assertEquals(before, Float.floatToIntBits(stat.getModifiedValue()));

        stat.modifyFlat("stockpile", 75.0F);
        assertEquals(75.0F, stat.getModifiedValue());

        stat.addTemporaryModFlat(0.5F, "raid_1", -30.0F);
        stat.advance(1.0F);
        assertEquals(75.0F, stat.getModifiedValue());
    }

    private static List<String> redirectTargetMethods() throws IOException {
        final ClassNode mixin = readClasspathClass(
                "github/kasuminova/ssoptimizer/mixin/combat/MutableStatMutationMixin");
        for (final MethodNode method : mixin.methods) {
            if (!method.name.equals("ssoptimizer$trackMutation") || method.visibleAnnotations == null) {
                continue;
            }
            for (final AnnotationNode annotation : method.visibleAnnotations) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")) {
                    continue;
                }
                final List<Object> values = annotation.values;
                for (int i = 0; i < values.size(); i += 2) {
                    if ("method".equals(values.get(i))) {
                        @SuppressWarnings("unchecked")
                        final List<String> methods = (List<String>) values.get(i + 1);
                        return methods;
                    }
                }
            }
        }
        throw new IllegalStateException("未能从 MutableStatMutationMixin 注解回读 redirect 目标列表");
    }

    private static ClassNode readClasspathClass(final String slashName) throws IOException {
        try (InputStream in = MutableStatMutationAnchorTest.class.getClassLoader()
                .getResourceAsStream(slashName + ".class")) {
            assertNotNull(in, "测试 classpath 必须包含类: " + slashName);
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static MethodNode findMethod(final ClassNode node, final String name, final String desc) {
        for (final MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }

    private static List<FieldInsnNode> trueWrites(final MethodNode method) {
        final List<FieldInsnNode> result = new ArrayList<>();
        for (final AbstractInsnNode insn : method.instructions) {
            if (isNeedsRecomputePut(insn)
                    && insn.getPrevious() != null
                    && insn.getPrevious().getOpcode() == Opcodes.ICONST_1) {
                result.add((FieldInsnNode) insn);
            }
        }
        return result;
    }

    private static int falseWrites(final MethodNode method) {
        int count = 0;
        for (final AbstractInsnNode insn : method.instructions) {
            if (isNeedsRecomputePut(insn)
                    && insn.getPrevious() != null
                    && insn.getPrevious().getOpcode() == Opcodes.ICONST_0) {
                count++;
            }
        }
        return count;
    }

    private static boolean isNeedsRecomputePut(final AbstractInsnNode insn) {
        if (insn.getOpcode() != Opcodes.PUTFIELD) {
            return false;
        }
        final FieldInsnNode field = (FieldInsnNode) insn;
        return field.owner.equals("com/fs/starfarer/api/combat/MutableStat")
                && field.name.equals("needsRecompute")
                && field.desc.equals("Z");
    }
}
