package github.kasuminova.ssoptimizer.asm.ai;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AiStaticsThreadLocalProcessor} 单元测试。
 * <p>
 * 验证 AIUtils 的四个静态字段访问重定向到 {@code AiThreadLocals} 的 getter/setter，
 * AttackAIModule 只重定向 AIUtils.null 开关；其余字段访问与目标类之外均保持不变/返回 null。
 */
class AiStaticsThreadLocalProcessorTest {

    private static final String AI_UTILS       = "com/fs/starfarer/combat/ai/AIUtils";
    private static final String ATTACK_MODULE  = "com/fs/starfarer/combat/ai/attack/AttackAIModule";
    private static final String LOCALS_OWNER   = "github/kasuminova/ssoptimizer/common/combat/ai/AiThreadLocals";

    /**
     * 构造假 AIUtils：声明 blockingShips、aimErrorOffset1/2、null 四个待重定向静态字段，
     * 外加不在重定向清单内的 aimErrorDivisor；方法内对每个字段做一次 GETSTATIC + PUTSTATIC。
     */
    private byte[] createFakeAiUtils() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, AI_UTILS, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "blockingShips", "Ljava/util/List;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "aimErrorOffset1", "F", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "aimErrorOffset2", "F", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "null", "Z", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "aimErrorDivisor", "F", null, null).visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "compute", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, AI_UTILS, "blockingShips", "Ljava/util/List;");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, AI_UTILS, "blockingShips", "Ljava/util/List;");
        mv.visitFieldInsn(Opcodes.GETSTATIC, AI_UTILS, "aimErrorOffset1", "F");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, AI_UTILS, "aimErrorOffset1", "F");
        mv.visitFieldInsn(Opcodes.GETSTATIC, AI_UTILS, "aimErrorOffset2", "F");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, AI_UTILS, "aimErrorOffset2", "F");
        mv.visitFieldInsn(Opcodes.GETSTATIC, AI_UTILS, "null", "Z");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, AI_UTILS, "null", "Z");
        mv.visitFieldInsn(Opcodes.GETSTATIC, AI_UTILS, "aimErrorDivisor", "F");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * 构造假 AttackAIModule：访问 AIUtils.null（读写）与 AIUtils.aimErrorOffset1（只读）。
     */
    private byte[] createFakeAttackAiModule() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, ATTACK_MODULE, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getAimPoint", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, AI_UTILS, "null", "Z");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, AI_UTILS, "null", "Z");
        mv.visitFieldInsn(Opcodes.GETSTATIC, AI_UTILS, "aimErrorOffset1", "F");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void redirectsAiUtilsStaticsToThreadLocals() {
        byte[] rewritten = new AiStaticsThreadLocalProcessor().process(createFakeAiUtils());
        assertNotNull(rewritten);

        ClassReader reader = new ClassReader(rewritten);
        final int[] getBlockingShips = {0}, setBlockingShips = {0};
        final int[] getAimErrorOffset1 = {0}, setAimErrorOffset1 = {0};
        final int[] getAimErrorOffset2 = {0}, setAimErrorOffset2 = {0};
        final int[] getNullFlag = {0}, setNullFlag = {0};
        final int[] aimErrorDivisorReads = {0};
        final int[] residualAiUtilsAccess = {0}; // 重定向字段的残留 GETSTATIC/PUTSTATIC 计数

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (!LOCALS_OWNER.equals(owner)) {
                            return;
                        }
                        switch (methodName + methodDesc) {
                            case "getBlockingShips()Ljava/util/List;" -> getBlockingShips[0]++;
                            case "setBlockingShips(Ljava/util/List;)V" -> setBlockingShips[0]++;
                            case "getAimErrorOffset1()F" -> getAimErrorOffset1[0]++;
                            case "setAimErrorOffset1(F)V" -> setAimErrorOffset1[0]++;
                            case "getAimErrorOffset2()F" -> getAimErrorOffset2[0]++;
                            case "setAimErrorOffset2(F)V" -> setAimErrorOffset2[0]++;
                            case "getNullFlag()Z" -> getNullFlag[0]++;
                            case "setNullFlag(Z)V" -> setNullFlag[0]++;
                            default -> { }
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
                        if (AI_UTILS.equals(owner) && "aimErrorDivisor".equals(fieldName)) {
                            aimErrorDivisorReads[0]++;
                        }
                        if (AI_UTILS.equals(owner)) {
                            residualAiUtilsAccess[0]++;
                        }
                    }
                };
            }
        }, 0);

        // 四个字段的 GETSTATIC/PUTSTATIC 全部重定向为 AiThreadLocals 的 get/set
        assertEquals(1, getBlockingShips[0]);
        assertEquals(1, setBlockingShips[0]);
        assertEquals(1, getAimErrorOffset1[0]);
        assertEquals(1, setAimErrorOffset1[0]);
        assertEquals(1, getAimErrorOffset2[0]);
        assertEquals(1, setAimErrorOffset2[0]);
        assertEquals(1, getNullFlag[0]);
        assertEquals(1, setNullFlag[0]);
        // aimErrorDivisor 不在重定向清单，原样保留
        assertEquals(1, aimErrorDivisorReads[0]);
        // 残留的 AIUtils 字段访问只有 aimErrorDivisor 这一处
        assertEquals(1, residualAiUtilsAccess[0]);
    }

    @Test
    void redirectsOnlyNullFlagInAttackAiModule() {
        byte[] rewritten = new AiStaticsThreadLocalProcessor().process(createFakeAttackAiModule());
        assertNotNull(rewritten);

        ClassReader reader = new ClassReader(rewritten);
        final int[] getNullFlag = {0}, setNullFlag = {0};
        final int[] aimErrorOffset1Reads = {0};

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (!LOCALS_OWNER.equals(owner)) {
                            return;
                        }
                        if ("getNullFlag".equals(methodName)) {
                            getNullFlag[0]++;
                        }
                        if ("setNullFlag".equals(methodName)) {
                            setNullFlag[0]++;
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
                        if (AI_UTILS.equals(owner) && "aimErrorOffset1".equals(fieldName)) {
                            aimErrorOffset1Reads[0]++;
                        }
                    }
                };
            }
        }, 0);

        // 只有 null 开关被重定向，aimErrorOffset1 保持不变
        assertEquals(1, getNullFlag[0]);
        assertEquals(1, setNullFlag[0]);
        assertEquals(1, aimErrorOffset1Reads[0]);
    }

    @Test
    void returnsNullForOtherClasses() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/fs/starfarer/combat/SomeOtherClass",
                null, "java/lang/Object", null);
        cw.visitEnd();
        assertNull(new AiStaticsThreadLocalProcessor().process(cw.toByteArray()));
    }
}
