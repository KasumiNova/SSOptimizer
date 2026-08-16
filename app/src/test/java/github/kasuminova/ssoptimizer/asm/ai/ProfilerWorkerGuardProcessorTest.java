package github.kasuminova.ssoptimizer.asm.ai;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProfilerWorkerGuardProcessor} 单元测试。
 * <p>
 * 验证 begin(String)V 与 end()V 方法头插入
 * {@code if (ParallelAiDispatcher.isWorkerThread()) return;} 守卫，
 * 其他方法不受影响，目标类之外返回 null。
 */
class ProfilerWorkerGuardProcessorTest {

    private static final String PROFILER       = "com/fs/profiler/Profiler";
    private static final String DISPATCH_OWNER = "github/kasuminova/ssoptimizer/common/combat/ai/ParallelAiDispatcher";

    /** 头部指令序列中 Label 的占位标记。 */
    private static final String LABEL_MARKER = "LABEL";

    /**
     * 构造假 Profiler：begin(String)V、end()V 与其他方法 other()V，方法体均为直接 RETURN。
     */
    private byte[] createFakeProfiler(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);

        MethodVisitor begin = cw.visitMethod(Opcodes.ACC_PUBLIC, "begin", "(Ljava/lang/String;)V", null, null);
        begin.visitCode();
        begin.visitInsn(Opcodes.RETURN);
        begin.visitMaxs(0, 0);
        begin.visitEnd();

        MethodVisitor end = cw.visitMethod(Opcodes.ACC_PUBLIC, "end", "()V", null, null);
        end.visitCode();
        end.visitInsn(Opcodes.RETURN);
        end.visitMaxs(0, 0);
        end.visitEnd();

        MethodVisitor other = cw.visitMethod(Opcodes.ACC_PUBLIC, "other", "()V", null, null);
        other.visitCode();
        other.visitInsn(Opcodes.RETURN);
        other.visitMaxs(0, 0);
        other.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 收集每个方法头部前四条指令的描述序列，用于断言守卫注入位置。 */
    private Map<String, List<String>> collectMethodHeads(byte[] classfile) {
        ClassReader reader = new ClassReader(classfile);
        Map<String, List<String>> heads = new TreeMap<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                List<String> head = new ArrayList<>(4);
                heads.put(name + desc, head);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (head.size() < 4) {
                            head.add("INVOKESTATIC " + owner + "." + methodName + methodDesc);
                        }
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label label) {
                        if (head.size() < 4) {
                            head.add(opcode == Opcodes.IFEQ ? "IFEQ" : "JUMP(" + opcode + ")");
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (head.size() < 4 && opcode == Opcodes.RETURN) {
                            head.add("RETURN");
                        }
                    }

                    @Override
                    public void visitLabel(Label label) {
                        if (head.size() < 4) {
                            head.add(LABEL_MARKER);
                        }
                    }
                };
            }
        }, 0);
        return heads;
    }

    @Test
    void guardsBeginAndEndMethodHeads() {
        byte[] rewritten = new ProfilerWorkerGuardProcessor().process(createFakeProfiler(PROFILER));
        assertNotNull(rewritten);

        Map<String, List<String>> heads = collectMethodHeads(rewritten);

        // begin 与 end 的方法头应为：isWorkerThread 判断 → IFEQ → RETURN（守卫分支）→ 原方法体入口 Label
        List<String> guardHead = List.of(
                "INVOKESTATIC " + DISPATCH_OWNER + ".isWorkerThread()Z",
                "IFEQ",
                "RETURN",
                LABEL_MARKER);
        assertEquals(guardHead, heads.get("begin(Ljava/lang/String;)V"));
        assertEquals(guardHead, heads.get("end()V"));
        // 其他方法不受影响，方法头仍为直接 RETURN
        assertEquals(List.of("RETURN"), heads.get("other()V"));
    }

    @Test
    void returnsNullForOtherClasses() {
        assertNull(new ProfilerWorkerGuardProcessor().process(
                createFakeProfiler("com/fs/profiler/OtherProfiler")));
    }
}
