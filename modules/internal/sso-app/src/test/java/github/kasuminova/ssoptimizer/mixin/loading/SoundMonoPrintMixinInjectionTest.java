package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sound 构造器 println 重定向的注入锚点核验。
 * <p>
 * 用 ASM 解析测试 classpath 上的真实 {@code sound.Sound} 字节码（非源码文本匹配），
 * 核验 {@code Sound(String, String, InputStream)} 构造器内恰好两次
 * {@code PrintStream.println(String)} 调用（{@code SoundMonoPrintMixin} 以 ordinal 0/1
 * 区分两处锚点，数量漂移会导致 Mixin 运行时拒绝），且两个格式化模板常量的顺序为
 * 「UI sound」在前、「NOT mono」在后（与 ordinal 语义注释一致）。
 */
class SoundMonoPrintMixinInjectionTest {

    private static final String CTOR_DESC = "(Ljava/lang/String;Ljava/lang/String;Ljava/io/InputStream;)V";
    private static final String PRINTLN = "java/io/PrintStream.println(Ljava/lang/String;)V";

    @Test
    void soundConstructorHasExactlyTwoPrintlnCallSites() throws IOException {
        MethodNode ctor = findMethod(GameClassNames.SOUND, "<init>", CTOR_DESC);
        int count = 0;
        for (AbstractInsnNode insn : ctor.instructions) {
            if (insn instanceof MethodInsnNode m
                    && (PRINTLN).equals(m.owner + "." + m.name + m.desc)) {
                count++;
            }
        }
        assertEquals(2, count, "Sound 构造器内 PrintStream.println(String) 调用点必须恰好两处");
    }

    @Test
    void uiMonoTemplatePrecedesNotMonoTemplate() throws IOException {
        MethodNode ctor = findMethod(GameClassNames.SOUND, "<init>", CTOR_DESC);
        List<String> templates = new ArrayList<>();
        for (AbstractInsnNode insn : ctor.instructions) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s
                    && (s.contains("is mono") || s.contains("is NOT mono"))) {
                templates.add(s);
            }
        }
        assertEquals(2, templates.size(), "构造器内必须恰好两个 mono 提示格式化模板");
        assertTrue(templates.get(0).startsWith("UI sound"), "ordinal 0 对应 UI mono 提示");
        assertTrue(templates.get(1).contains("NOT mono"), "ordinal 1 对应 NOT mono 提示");
    }

    private static MethodNode findMethod(final String slashClassName, final String name,
                                         final String desc) throws IOException {
        ClassNode node = readClass(slashClassName);
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        throw new AssertionError(slashClassName + "." + name + desc + " 必须存在（println 重定向注入点）");
    }

    private static ClassNode readClass(final String slashClassName) throws IOException {
        String resource = slashClassName + ".class";
        try (InputStream in = SoundMonoPrintMixinInjectionTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "测试 classpath 必须包含游戏类: " + resource);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
