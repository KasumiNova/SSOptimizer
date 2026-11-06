package github.kasuminova.ssoptimizer.asm.util;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorCtorSanitizeProcessorTest {

    private static final String SAMPLE_CLASS = "com/fs/graphics/util/RenderStateUtils";
    private static final String FIXTURE_CLASS = "github/kasuminova/ssoptimizer/asm/util/ColorCtorFixture";
    private static final String FIXTURE_CALLER_CLASS = FIXTURE_CLASS + "$Caller";

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
                "每个目标构造调用必须一一对应工厂调用");
        assertEquals(countMethods(original), countMethods(woven), "改写不得增删方法");
        assertBytecodeValid(woven);
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
    void fixtureAppliesSanitizationThroughNestedAndTernaryCalls() throws IOException {
        byte[] woven = new ColorCtorSanitizeProcessor().process(readClassBytes(FIXTURE_CLASS));
        assertNotNull(woven, "夹具类的目标构造调用必须被改写");

        assertEquals(0, countColorCtorInvocations(woven), "外层夹具类不得残留目标构造调用");
        assertBytecodeValid(woven);

        final FixtureCaller caller = loadFixtureCaller(woven);
        assertEquals(new Color(0, 4, 5, 6), caller.nested(),
                "嵌套构造的外层越界分量必须经工厂钳制");
        assertEquals(new Color(1.0f, 0.0f, 1.0f, 0.5f), caller.floatTernary(true),
                "三元表达式产生的越界浮点分量必须经工厂钳制");

        assertNull(new ColorCtorSanitizeProcessor()
                .process(readClassBytes(FIXTURE_CLASS + "$Chained")),
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
        assertBytecodeValid(woven);
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

    private static FixtureCaller loadFixtureCaller(final byte[] fixtureBytes) {
        final ByteArrayLoader loader = new ByteArrayLoader(ColorCtorSanitizeProcessorTest.class.getClassLoader());
        loader.define(FIXTURE_CLASS.replace('/', '.'), fixtureBytes);
        final String callerName = FIXTURE_CALLER_CLASS.replace('/', '.');
        final Class<?> callerClass = loader.define(callerName,
                github.kasuminova.ssoptimizer.testing.ClassInstances.publishInstanceOnInit(fixtureCallerBytes()));
        return github.kasuminova.ssoptimizer.testing.ClassInstances.instantiate(FixtureCaller.class, callerClass);
    }

    private static byte[] fixtureCallerBytes() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                FIXTURE_CALLER_CLASS, null, "java/lang/Object", new String[]{Type.getInternalName(FixtureCaller.class)});
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        final MethodVisitor nested = writer.visitMethod(Opcodes.ACC_PUBLIC, "nested",
                "()Ljava/awt/Color;", null, null);
        nested.visitCode();
        nested.visitMethodInsn(Opcodes.INVOKESTATIC, FIXTURE_CLASS, "nested", "()Ljava/awt/Color;", false);
        nested.visitInsn(Opcodes.ARETURN);
        nested.visitMaxs(0, 0);
        nested.visitEnd();

        final MethodVisitor floatTernary = writer.visitMethod(Opcodes.ACC_PUBLIC, "floatTernary",
                "(Z)Ljava/awt/Color;", null, null);
        floatTernary.visitCode();
        floatTernary.visitVarInsn(Opcodes.ILOAD, 1);
        floatTernary.visitMethodInsn(Opcodes.INVOKESTATIC, FIXTURE_CLASS,
                "floatTernary", "(Z)Ljava/awt/Color;", false);
        floatTernary.visitInsn(Opcodes.ARETURN);
        floatTernary.visitMaxs(0, 0);
        floatTernary.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void assertBytecodeValid(final byte[] bytes) {
        final java.io.StringWriter analysisOut = new java.io.StringWriter();
        org.objectweb.asm.util.CheckClassAdapter.verify(
                new ClassReader(bytes), false, new java.io.PrintWriter(analysisOut));
        assertTrue(analysisOut.toString().isEmpty(), "织入产物必须零校验错误: " + analysisOut);
    }

    private static int countMethods(final byte[] classBytes) {
        return classNode(classBytes).methods.size();
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

    public interface FixtureCaller {
        Color nested();

        Color floatTernary(boolean condition);
    }

    private static final class ByteArrayLoader extends ClassLoader {
        ByteArrayLoader(final ClassLoader parent) {
            super(parent);
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
