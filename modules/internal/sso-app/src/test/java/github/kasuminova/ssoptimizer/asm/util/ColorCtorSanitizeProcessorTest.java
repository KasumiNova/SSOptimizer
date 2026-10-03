package github.kasuminova.ssoptimizer.asm.util;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link ColorCtorSanitizeProcessor} 对真实游戏字节码的织入验证。
 * <p>
 * 以测试 classpath 上的 named 游戏类字节驱动处理器，核验目标构造调用被逐一
 * 改写为 {@code ColorSanitizer.create} 工厂调用（含 LDC site 末参），以及
 * 无 Color 引用类的预过滤跳过与净化器自身排除。
 */
class ColorCtorSanitizeProcessorTest {

    /** 含大量 {@code new Color(int,int,int,int)} 的真实游戏类（渲染工具类）。 */
    private static final String SAMPLE_CLASS = "com/fs/graphics/util/RenderStateUtils";

    private static final String COLOR_OWNER = "java/awt/Color";
    private static final Set<String> TARGET_CTOR_DESCS = Set.of("(III)V", "(IIII)V", "(FFF)V", "(FFFF)V");

    @Test
    void realClassColorCtorsAreRewrittenToSanitizerFactory() throws IOException {
        byte[] original = readClassBytes(SAMPLE_CLASS);
        int originalCtors = countColorCtorInvocations(original);
        assertTrue(originalCtors > 0, "样本类必须含有目标 Color 构造调用点");

        byte[] woven = new ColorCtorSanitizeProcessor().process(original);
        assertNotNull(woven, "含 Color 构造的真实类必须被改写");

        assertEquals(0, countColorCtorInvocations(woven), "目标构造调用必须全部被改写");
        assertEquals(originalCtors, countSanitizerFactoryCalls(woven),
                "每个构造调用点必须一一对应工厂调用");
        assertEveryFactoryCallHasSiteArg(woven);
        assertEquals(countMethods(original), countMethods(woven), "改写不得增删方法");
    }

    @Test
    void classWithoutColorReferenceIsSkippedByPrefilter() throws IOException {
        assertNull(new ColorCtorSanitizeProcessor()
                .process(readClassBytes("github/kasuminova/ssoptimizer/api/AsmClassProcessor")),
                "无 Color 常量引用的类必须返回 null（预过滤跳过）");
    }

    @Test
    void sanitizerHelperItselfIsExcluded() throws IOException {
        // 净化器自身引用 Color 构造，必须被显式排除防止自改写递归
        assertNull(new ColorCtorSanitizeProcessor()
                .process(readClassBytes(ColorCtorSanitizeProcessor.HELPER_OWNER)));
    }

    @Test
    void fixtureCoversNestedTernaryAndChainedSuperForms() throws IOException {
        byte[] woven = new ColorCtorSanitizeProcessor()
                .process(readClassBytes("github/kasuminova/ssoptimizer/asm/util/ColorCtorFixture"));
        assertNotNull(woven, "夹具类的目标构造调用必须被改写");

        // nested() 内层 (III) + 外层 (IIII) 两个调用点 + floatTernary() 一个 = 3 次工厂调用
        assertEquals(3, countSanitizerFactoryCalls(woven),
                "嵌套构造与三元参数构造必须全部改写为工厂调用");
        assertEquals(0, countColorCtorInvocations(woven),
                "外层夹具类的目标构造调用必须全部改写、无残留");
        assertEveryFactoryCallHasSiteArg(woven);

        // 织入产物必须通过 ASM 数据流校验（帧重算后栈结构合法）
        java.io.StringWriter analysisOut = new java.io.StringWriter();
        org.objectweb.asm.util.CheckClassAdapter.verify(
                new ClassReader(woven), false, new java.io.PrintWriter(analysisOut));
        assertEquals("", analysisOut.toString(), "织入产物必须零校验错误: " + analysisOut);

        // 链式子类同样必须被正确处理（其 super 调用保持原样、不织入）
        assertNull(new ColorCtorSanitizeProcessor()
                .process(readClassBytes("github/kasuminova/ssoptimizer/asm/util/ColorCtorFixture$Chained")),
                "仅含 super 链式调用的子类无可改写调用点，必须返回 null");
    }

    /** 统计四种目标描述符的 Color 构造调用数。 */
    private static int countColorCtorInvocations(final byte[] classBytes) {
        int count = 0;
        for (MethodNode method : classNode(classBytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode && insn.getOpcode() == Opcodes.INVOKESPECIAL) {
                    MethodInsnNode m = (MethodInsnNode) insn;
                    if (COLOR_OWNER.equals(m.owner) && "<init>".equals(m.name)
                            && TARGET_CTOR_DESCS.contains(m.desc)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** 统计净化工厂调用数。 */
    private static int countSanitizerFactoryCalls(final byte[] classBytes) {
        int count = 0;
        for (MethodNode method : classNode(classBytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode && insn.getOpcode() == Opcodes.INVOKESTATIC) {
                    MethodInsnNode m = (MethodInsnNode) insn;
                    if (ColorCtorSanitizeProcessor.HELPER_OWNER.equals(m.owner) && "create".equals(m.name)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** 核验每个工厂调用的紧邻前驱都是 LDC 烧录的 site 字符串（{@code 类名#方法名@序号}）。 */
    private static void assertEveryFactoryCallHasSiteArg(final byte[] classBytes) {
        for (MethodNode method : classNode(classBytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode) || insn.getOpcode() != Opcodes.INVOKESTATIC) {
                    continue;
                }
                MethodInsnNode m = (MethodInsnNode) insn;
                if (!ColorCtorSanitizeProcessor.HELPER_OWNER.equals(m.owner) || !"create".equals(m.name)) {
                    continue;
                }
                AbstractInsnNode prev = m.getPrevious();
                if (!(prev instanceof LdcInsnNode) || !(((LdcInsnNode) prev).cst instanceof String)) {
                    fail("工厂调用缺失 LDC site 末参: " + method.name);
                }
                String site = (String) ((LdcInsnNode) prev).cst;
                if (!site.contains("#") || !site.contains("@")) {
                    fail("site 标识格式不符（类名#方法名@序号）: " + site);
                }
            }
        }
    }

    private static int countMethods(final byte[] classBytes) {
        return classNode(classBytes).methods.size();
    }

    private static ClassNode classNode(final byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node;
    }

    private static byte[] readClassBytes(final String slashClassName) throws IOException {
        String resource = slashClassName + ".class";
        try (InputStream in = ColorCtorSanitizeProcessorTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "测试 classpath 必须包含类: " + resource);
            return in.readAllBytes();
        }
    }
}
