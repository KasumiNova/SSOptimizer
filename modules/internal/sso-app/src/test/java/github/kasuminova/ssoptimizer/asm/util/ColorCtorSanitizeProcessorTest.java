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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ColorCtorSanitizeProcessorTest {

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
        assertNull(new ColorCtorSanitizeProcessor()
                .process(readClassBytes(ColorCtorSanitizeProcessor.HELPER_OWNER)));
    }

    @Test
    void fixtureCoversNestedTernaryAndChainedSuperForms() throws IOException {
        byte[] woven = new ColorCtorSanitizeProcessor()
                .process(readClassBytes("github/kasuminova/ssoptimizer/asm/util/ColorCtorFixture"));
        assertNotNull(woven, "夹具类的目标构造调用必须被改写");

        assertEquals(3, countSanitizerFactoryCalls(woven),
                "嵌套构造与三元参数构造必须全部改写为工厂调用");
        assertEquals(0, countColorCtorInvocations(woven),
                "外层夹具类的目标构造调用必须全部改写、无残留");
        assertEveryFactoryCallHasSiteArg(woven);

        java.io.StringWriter analysisOut = new java.io.StringWriter();
        org.objectweb.asm.util.CheckClassAdapter.verify(
                new ClassReader(woven), false, new java.io.PrintWriter(analysisOut));
        assertEquals("", analysisOut.toString(), "织入产物必须零校验错误: " + analysisOut);

        assertLinks("github.kasuminova.ssoptimizer.asm.util.ColorCtorFixture", woven);

        assertNull(new ColorCtorSanitizeProcessor()
                .process(readClassBytes("github/kasuminova/ssoptimizer/asm/util/ColorCtorFixture$Chained")),
                "仅含 super 链式调用的子类无可改写调用点，必须返回 null");
    }

    @Test
    void mergeFramesKeepPreciseTypesAfterWeaving() throws IOException {
        final String target = "com/fs/starfarer/loading/WeaponSpecLoader";
        byte[] original = readClassBytes(target);
        byte[] woven = new ColorCtorSanitizeProcessor().process(original);
        assertNotNull(woven, "目标类必须被改写");

        assertEquals(frameTypeNames(original, "loadWeaponSpec"), frameTypeNames(woven, "loadWeaponSpec"),
                "织入后 loadWeaponSpec 的帧内类型集合必须与原类一致（不得降级为 Object）");
        assertLinks(target.replace('/', '.'), woven);
    }

    private static java.util.Set<String> frameTypeNames(final byte[] classBytes, final String methodName) {
        java.util.Set<String> types = new java.util.TreeSet<>();
        for (MethodNode method : classNode(classBytes).methods) {
            if (!method.name.equals(methodName)) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.FrameNode) {
                    org.objectweb.asm.tree.FrameNode frame = (org.objectweb.asm.tree.FrameNode) insn;
                    collectFrameTypes(types, frame.local);
                    collectFrameTypes(types, frame.stack);
                }
            }
        }
        return types;
    }

    private static void collectFrameTypes(final java.util.Set<String> types, final List<Object> entries) {
        if (entries == null) {
            return;
        }
        for (Object entry : entries) {
            if (entry instanceof String) {
                types.add((String) entry);
            } else if (entry instanceof Integer) {
                types.add("const:" + entry);
            } else {
                types.add("uninitialized");
            }
        }
    }

    private static void assertLinks(final String binaryName, final byte[] bytes) {
        ClassLoader parent = ColorCtorSanitizeProcessorTest.class.getClassLoader();
        ClassLoader loader = new ClassLoader(parent) {
            @Override
            public Class<?> loadClass(final String name) throws ClassNotFoundException {
                if (!name.equals(binaryName)) {
                    return super.loadClass(name);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
                }
            }
        };
        try {
            Class.forName(binaryName, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

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
