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

/**
 * {@link MutableStatMutationMixin} 的字节码锚点与原版语义前提核验。
 * <p>
 * 代际计数器的正确性取决于「redirect 覆盖全部 {@code needsRecompute = true} 赋值点、
 * 且仅覆盖真实修改」：redirect 目标列表内联于 Mixin 注解（数组无编译期常量表达式），
 * 此处从 Mixin 类字节码回读注解中的 method 列表，再对测试 classpath 上 named jar 的
 * 真实 {@code MutableStat} 逐一核验，并以真实 {@link MutableStatWithTempMods}
 * 验证「同值覆写/时间递减不产生代际」赖以成立的原版行为。
 */
class MutableStatMutationAnchorTest {

    // ---------- 字节码锚点 ----------

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

        // 字节码中所有「写 needsRecompute = true」的方法必须恰好等于 redirect 目标集合，
        // 漏任何一个都会导致对应修改路径不产生代际增长（签名漏检）
        final Set<String> actual = new HashSet<>();
        for (final MethodNode method : node.methods) {
            if (!trueWrites(method).isEmpty()) {
                actual.add(method.name + method.desc);
            }
        }
        assertEquals(expected, actual, "needsRecompute = true 赋值方法集合必须与 redirect 目标一致");

        // recompute() 与构造器（字段初值 false）只允许写 false（不属代际路径），
        // 其余方法不得出现 false 写入
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
        // MutableStatWithTempMods 的 temp mod 路径全部委托基类 modify*/unmodify*，
        // 基类 redirect 即可覆盖；若子类出现自有字段写则必须扩展目标集合
        final ClassNode node = readClasspathClass("com/fs/starfarer/api/combat/MutableStatWithTempMods");
        for (final MethodNode method : node.methods) {
            assertTrue(trueWrites(method).isEmpty(),
                    "MutableStatWithTempMods." + method.name + method.desc + " 不得自有 needsRecompute 写入");
        }
    }

    // ---------- 原版语义前提（真实统计实例） ----------

    @Test
    void advanceWithoutExpiryKeepsValueBitStable() {
        // 「temp mod timeRemaining 逐帧递减不改变计算结果」——代际不计增的前提
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

        // 同值覆写：原版走 desc-only 分支，不置 needsRecompute —— 代际不计增
        stat.modifyFlat("stockpile", 50.0F);
        assertEquals(before, Float.floatToIntBits(stat.getModifiedValue()));

        // 异值覆写（LocalResourcesSubmarketPlugin 每帧的 deficit 更新）必须产生代际增长；
        // 同 source 覆写是替换而非累加，值由 50 变为 75
        stat.modifyFlat("stockpile", 75.0F);
        assertEquals(75.0F, stat.getModifiedValue());

        // temp mod 到期移除改变计算结果 —— 代际必须计增
        stat.addTemporaryModFlat(0.5F, "raid_1", -30.0F);
        stat.advance(1.0F);
        assertEquals(75.0F, stat.getModifiedValue());
    }

    // ---------- 工具 ----------

    /** 从 MutableStatMutationMixin 类字节码回读 @Redirect 注解的 method 列表。 */
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

    /** 收集方法内全部 {@code needsRecompute = true} 的 PUTAFIELD 指令。 */
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
