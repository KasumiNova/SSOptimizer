package github.kasuminova.ssoptimizer.standalone.remap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.*;
import java.lang.invoke.MethodHandles;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class MemberNameComparisonsTest {
    private static final String BRIDGE = "github/kasuminova/ssoptimizer/standalone/remap/reflect/ReflectionNames";

    @Test void bothOrdersPreserveOriginalCallsAndArgumentOrigins() throws Exception {
        for (boolean reversed : List.of(false, true)) {
            for (String type : List.of("Method", "Field", "Member")) {
                ClassNode node = node();
                MethodNode m = compare(node, reversed, type, false);
                List<MethodInsnNode> original = calls(m);
                MemberNameComparisons.rewrite(node);
                assertEquals(3, calls(m).size());
                assertSame(original.get(0), calls(m).get(0));
                assertSame(original.get(1), calls(m).get(1));
                MethodInsnNode bridge = calls(m).getLast();
                assertEquals(BRIDGE, bridge.owner);
                assertEquals("comparison", bridge.name);
                assertEquals("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Z)Z", bridge.desc);
                new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
                var frames = new Analyzer<>(new SourceInterpreter(ASM9) {
                    @Override public SourceValue newParameterValue(boolean instance, int local, Type value) {
                        return new SourceValue(1, new VarInsnNode(ALOAD, local));
                    }
                    @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) { return value; }
                    @Override public SourceValue unaryOperation(AbstractInsnNode instruction, SourceValue value) {
                        return instruction.getOpcode() == CHECKCAST ? value : super.unaryOperation(instruction, value);
                    }
                }).analyze(node.name, m);
                Frame<SourceValue> frame = frames[m.instructions.indexOf(bridge)];
                assertEquals(0, ((VarInsnNode) frame.getStack(0).insns.iterator().next()).var);
                assertEquals(1, ((VarInsnNode) frame.getStack(1).insns.iterator().next()).var);
                assertEquals(Set.of(original.get(0)), frame.getStack(2).insns);
                assertEquals(Set.of(original.get(1)), frame.getStack(3).insns);
                int count = m.instructions.size();
                MemberNameComparisons.rewrite(node);
                assertEquals(count, m.instructions.size());
            }
        }
    }

    @Test void provesNullableMethodHandleHelperWithoutChangingLogsOrExceptionHandlers() throws Exception {
        ClassNode node = fixture("getName", true);
        MethodNode helper = node.methods.stream().filter(m -> m.name.equals("read")).findFirst().orElseThrow();
        MethodNode log = node.methods.stream().filter(m -> m.name.equals("log")).findFirst().orElseThrow();
        AbstractInsnNode[] helperCode = helper.instructions.toArray();
        AbstractInsnNode[] logCode = log.instructions.toArray();
        int handlers = helper.tryCatchBlocks.size();
        MemberNameComparisons.rewrite(node);
        assertArrayEquals(helperCode, helper.instructions.toArray());
        assertArrayEquals(logCode, log.instructions.toArray());
        assertEquals(handlers, helper.tryCatchBlocks.size());
        assertEquals(2, node.methods.stream().flatMap(m -> calls(m).stream()).filter(c -> c.owner.equals(BRIDGE)).count());
        for (MethodNode m : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
        ClassWriter output = new ClassWriter(0);
        node.accept(output);
        MethodHandles.lookup().defineHiddenClass(output.toByteArray(), false);
        ClassNode reread = new ClassNode();
        new ClassReader(output.toByteArray()).accept(reread, 0);
        for (MethodNode m : reread.methods) new Analyzer<>(new BasicVerifier()).analyze(reread.name, m);
    }

    @Test void rejectsUnprovenHandleAndMutableHandleField() {
        for (ClassNode node : List.of(fixture("toString", true), fixture("getName", false))) {
            MemberNameComparisons.rewrite(node);
            assertEquals(0, node.methods.stream().flatMap(m -> calls(m).stream()).filter(c -> c.owner.equals(BRIDGE)).count());
        }
    }

    @Test void rejectsMergedNamesAndCrossBranchCapture() {
        ClassNode node = node();
        MethodNode m = compare(node, true, "Method", false);
        MethodInsnNode producer = calls(m).getFirst();
        LabelNode label = new LabelNode();
        m.instructions.insert(producer, label);
        m.instructions.insert(producer, new JumpInsnNode(GOTO, label));
        MemberNameComparisons.rewrite(node);
        assertEquals(2, calls(m).size());
    }

    @Test void keepsUiNameAndCustomEqualsUntouched() {
        ClassNode node = node();
        MethodNode m = compare(node, false, "Method", false);
        calls(m).getFirst().owner = "java/lang/Class";
        AbstractInsnNode[] original = m.instructions.toArray();
        MemberNameComparisons.rewrite(node);
        assertArrayEquals(original, m.instructions.toArray());
        ClassNode custom = node();
        MethodNode c = compare(custom, true, "Method", false);
        calls(c).getLast().owner = "java/lang/Object";
        original = c.instructions.toArray();
        MemberNameComparisons.rewrite(custom);
        assertArrayEquals(original, c.instructions.toArray());
    }

    @Test void supportsTypedHandlesAndPublicTypedWrappers() throws Exception {
        ClassNode node = fixture("getName", true);
        MethodNode typed = new MethodNode(ACC_PUBLIC | ACC_STATIC, "typed", "(Ljava/lang/reflect/Method;)Ljava/lang/String;", null, null);
        node.methods.add(typed);
        typed.visitFieldInsn(GETSTATIC, node.name, "reader", "Ljava/lang/invoke/MethodHandle;");
        typed.visitVarInsn(ALOAD, 0);
        typed.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", "(Ljava/lang/reflect/Method;)Ljava/lang/String;", false);
        typed.visitInsn(ARETURN); typed.visitMaxs(2, 1);
        MethodNode compare = new MethodNode(ACC_STATIC, "typedCompare", "(Ljava/lang/reflect/Method;Ljava/lang/String;)Z", null, null);
        node.methods.add(compare);
        compare.visitVarInsn(ALOAD, 1); compare.visitVarInsn(ALOAD, 0);
        compare.visitMethodInsn(INVOKESTATIC, node.name, "typed", typed.desc, false);
        compare.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
        compare.visitInsn(IRETURN); compare.visitMaxs(2, 2);
        MemberNameComparisons.rewrite(node);
        assertEquals("comparison", calls(compare).getLast().name);
        new Analyzer<>(new BasicVerifier()).analyze(node.name, compare);
    }

    @Test void refusesANameMergedWithAnUnrelatedString() {
        ClassNode node = node();
        MethodNode m = new MethodNode(ACC_STATIC, "merged", "(Ljava/lang/reflect/Method;Ljava/lang/String;Z)Z", null, null);
        node.methods.add(m);
        Label alternative = new Label(), join = new Label();
        m.visitVarInsn(ILOAD, 2); m.visitJumpInsn(IFEQ, alternative);
        m.visitVarInsn(ALOAD, 0); m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "getName", "()Ljava/lang/String;", false);
        m.visitJumpInsn(GOTO, join); m.visitLabel(alternative); m.visitLdcInsn("UI caption");
        m.visitLabel(join); m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
        m.visitInsn(IRETURN); m.visitMaxs(2, 3);
        AbstractInsnNode[] original = m.instructions.toArray();
        MemberNameComparisons.rewrite(node);
        assertArrayEquals(original, m.instructions.toArray());
    }

    @Test void reusesOriginalValueWhenTheNameIsAlsoLogged() throws Exception {
        ClassNode node = node();
        MethodNode m = compare(node, true, "Method", false);
        MethodInsnNode source = calls(m).getFirst();
        InsnList log = new InsnList();
        log.add(new InsnNode(DUP));
        log.add(new MethodInsnNode(INVOKESTATIC, "example/Logger", "log", "(Ljava/lang/Object;)V", false));
        m.instructions.insert(source, log); m.maxStack++;
        MemberNameComparisons.rewrite(node);
        assertEquals(List.of("getName", "log", "equals", "comparison"), calls(m).stream().map(c -> c.name).toList());
        new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
    }

    @Test void leavesUnsupportedAnalysisAndUnrelatedMalformedMethodsUnchanged() {
        ClassNode node = node();
        compare(node, true, "Method", false);
        MethodNode unrelated = new MethodNode(ACC_STATIC, "unrelated", "()V", null, null);
        unrelated.visitInsn(POP); unrelated.visitInsn(RETURN); unrelated.visitMaxs(0, 0);
        node.methods.add(unrelated);
        MemberNameComparisons.rewrite(node);
        assertEquals("comparison", calls(node.methods.getFirst()).getLast().name);
        ClassNode unsupported = node();
        MethodNode broken = compare(unsupported, true, "Method", false);
        broken.maxStack = 0;
        AbstractInsnNode[] original = broken.instructions.toArray();
        MemberNameComparisons.rewrite(unsupported);
        assertArrayEquals(original, broken.instructions.toArray());
    }

    private static ClassNode fixture(String handleName, boolean immutable) {
        ClassNode node = node();
        node.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC | (immutable ? ACC_FINAL : 0), "reader", "Ljava/lang/invoke/MethodHandle;", null, null));
        MethodNode init = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
        node.methods.add(init);
        init.visitInsn(ACONST_NULL); init.visitVarInsn(ASTORE, 0);
        Label start = new Label(), end = new Label(), handler = new Label(), done = new Label();
        init.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        init.visitLabel(start);
        init.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
        init.visitLdcInsn("java.lang.reflect.Method"); init.visitInsn(ICONST_0); init.visitInsn(ACONST_NULL);
        init.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
        init.visitLdcInsn(handleName); init.visitLdcInsn(Type.getType(String.class));
        init.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodType", "methodType", "(Ljava/lang/Class;)Ljava/lang/invoke/MethodType;", false);
        init.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false);
        init.visitVarInsn(ASTORE, 0); init.visitLabel(end); init.visitJumpInsn(GOTO, done);
        init.visitLabel(handler); init.visitInsn(POP); init.visitLabel(done);
        init.visitVarInsn(ALOAD, 0); init.visitFieldInsn(PUTSTATIC, node.name, "reader", "Ljava/lang/invoke/MethodHandle;"); init.visitInsn(RETURN); init.visitMaxs(4, 1);
        MethodNode helper = new MethodNode(ACC_PRIVATE | ACC_STATIC, "read", "(Ljava/lang/Object;)Ljava/lang/String;", null, null);
        node.methods.add(helper);
        Label call = new Label(), finish = new Label(), caught = new Label();
        helper.visitFieldInsn(GETSTATIC, node.name, "reader", "Ljava/lang/invoke/MethodHandle;"); helper.visitJumpInsn(IFNONNULL, call);
        helper.visitInsn(ACONST_NULL); helper.visitInsn(ARETURN);
        helper.visitTryCatchBlock(call, finish, caught, "java/lang/Throwable"); helper.visitLabel(call);
        helper.visitFieldInsn(GETSTATIC, node.name, "reader", "Ljava/lang/invoke/MethodHandle;"); helper.visitVarInsn(ALOAD, 0);
        helper.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invoke", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        helper.visitLabel(finish); helper.visitInsn(ARETURN); helper.visitLabel(caught); helper.visitInsn(POP);
        helper.visitInsn(ACONST_NULL); helper.visitInsn(ARETURN); helper.visitMaxs(2, 1);
        compare(node, true, "Method", true); compare(node, false, "Method", true);
        MethodNode log = new MethodNode(ACC_STATIC, "log", "(Ljava/lang/Object;)Ljava/lang/String;", null, null);
        node.methods.add(log); log.visitVarInsn(ALOAD, 0); log.visitMethodInsn(INVOKESTATIC, node.name, "read", helper.desc, false); log.visitInsn(ARETURN); log.visitMaxs(1, 1);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        ClassNode parsed = new ClassNode(); new ClassReader(writer.toByteArray()).accept(parsed, 0); return parsed;
    }

    private static ClassNode node() {
        ClassNode node = new ClassNode();
        node.visit(V17, ACC_PUBLIC, "github/kasuminova/ssoptimizer/standalone/remap/MemberFixture", null, "java/lang/Object", null);
        return node;
    }

    private static MethodNode compare(ClassNode node, boolean reversed, String type, boolean helper) {
        MethodNode m = new MethodNode(ACC_STATIC, reversed ? "reverse" : "forward", "(Ljava/lang/Object;Ljava/lang/String;)Z", null, null);
        node.methods.add(m);
        if (reversed) m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 0);
        if (helper) m.visitMethodInsn(INVOKESTATIC, node.name, "read", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        else {
            m.visitTypeInsn(CHECKCAST, "java/lang/reflect/" + type);
            m.visitMethodInsn(type.equals("Member") ? INVOKEINTERFACE : INVOKEVIRTUAL, "java/lang/reflect/" + type, "getName", "()Ljava/lang/String;", type.equals("Member"));
        }
        if (!reversed) m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false); m.visitInsn(IRETURN); m.visitMaxs(2, 2); return m;
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call) result.add(call);
        return result;
    }
}
